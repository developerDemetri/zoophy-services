package edu.asu.zoophy.pipeline;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;

/**
 * Responsible for running BEAST processes
 * @author devdemetri
 */
public class BeastRunner {
	
	private final String JOB_LOG_DIR;
	private final String BEAST_SCRIPTS_DIR;
	private final String SPREAD3;
	private final String WORLD_GEOJSON;
	private final String RENDER_DIR;
	private final String FIGTREE_TEMPLATE;
	private final static String ALIGNED_FASTA = "-aligned.fasta";
	private final static String INPUT_XML = ".xml";
	private final static String OUTPUT_TREES = "-aligned.trees";
	private final static String RESULT_TREE = ".tree";
	
	private final Logger log;
	private final ZooPhyMailer mailer;
	private final ZooPhyJob job;
	private Set<String> filesToCleanup;
	private File logFile;
	private Tailer tail = null;
	private Tailer rateTail = null;
	private Process beastProcess;
	private boolean wasKilled = false;
	
	public BeastRunner(ZooPhyJob job, ZooPhyMailer mailer) throws PipelineException {
		PropertyProvider provider = PropertyProvider.getInstance();
		JOB_LOG_DIR = provider.getProperty("job.logs.dir");
		BEAST_SCRIPTS_DIR = provider.getProperty("beast.scripts.dir");
		WORLD_GEOJSON = provider.getProperty("geojson.location");
		RENDER_DIR = provider.getProperty("spread3.result.dir");
		FIGTREE_TEMPLATE = System.getProperty("user.dir")+"/Templates/figtreeBlock.template";
		SPREAD3 = System.getProperty("user.dir")+"/spread.jar";
		log = Logger.getLogger("BeastRunner");
		this.mailer = mailer;
		this.job = job;
		filesToCleanup = new LinkedHashSet<String>();
	}
	
	/**
	 * Runs the BEAST process
	 * @return resulting Tree File
	 * @throws BeastException
	 */
	public File run() throws BeastException {
		String resultingTree = null;
		FileHandler fileHandler = null;
		try {
			logFile = new File(JOB_LOG_DIR+job.getID()+".log");
			fileHandler = new FileHandler(JOB_LOG_DIR+job.getID()+".log", true);
			SimpleFormatter formatter = new SimpleFormatter();
	        fileHandler.setFormatter(formatter);
	        log.addHandler(fileHandler);
	        log.setUseParentHandlers(false);
			log.info("Starting the BEAST process...");
			runBeastGen(job.getID()+ALIGNED_FASTA, job.getID()+INPUT_XML);
			log.info("Adding location trait...");
			DiscreteTraitInserter traitInserter = new DiscreteTraitInserter(job);
			traitInserter.addLocation();
			log.info("Location trait added.");
			runBeast(job.getID());
			if (wasKilled || !PipelineManager.checkProcess(job.getID())) {
				throw new BeastException("Job was stopped!", "Job was stopped!");
			}
			resultingTree = runTreeAnnotator(job.getID()+OUTPUT_TREES);
			File tree = new File(resultingTree);
			if (tree.exists()) {
				annotateTreeFile(resultingTree);
				runSpread();
				log.info("BEAST process complete.");
			}
			else {
				log.log(Level.SEVERE, "TreeAnnotator did not proudce .tree file!");
				throw new BeastException("TreeAnnotator did not proudce .tree file!", null);
			}
			return tree;
		}
		catch (Exception e) {
			log.log(Level.SEVERE, "BEAST process failed: "+e.getMessage());
			throw new BeastException("BEAST process failed: "+e.getMessage(), null);
		}
		finally {
			if (tail != null) {
				tail.stop();
			}
			if (rateTail != null) {
				rateTail.stop();
			}
			if (fileHandler != null) {
				fileHandler.close();
			}
			cleanupBeast();
		}
	}
	
	/**
	 * Generates an input.xml file to feed into BEAST
	 * @param fastaFile
	 * @param beastInput
	 * @throws BeastException
	 * @throws IOException 
	 * @throws InterruptedException 
	 */
	private void runBeastGen(String fastaFile, String beastInput) throws BeastException, IOException, InterruptedException {
		String workingDir =  "../ZooPhyJobs/";
		File beastGenDir = new File(System.getProperty("user.dir")+"/BeastGen");
		filesToCleanup.add(workingDir+fastaFile);
		log.info("Running BEASTGen...");
		ProcessBuilder builder = new ProcessBuilder("java", "-jar", "beastgen.jar", "-date_order", "4", "beastgen.template", workingDir+fastaFile, workingDir+beastInput).directory(beastGenDir);
		builder.redirectOutput(Redirect.appendTo(logFile));
		builder.redirectError(Redirect.appendTo(logFile));
		log.info("Starting Process: "+builder.command().toString());
		Process beastGenProcess = builder.start();
		PipelineManager.setProcess(job.getID(), beastGenProcess);
		beastGenProcess.waitFor();
		if (beastGenProcess.exitValue() != 0) {
			log.log(Level.SEVERE, "BeastGen failed! with code: "+beastGenProcess.exitValue());
			throw new BeastException("BeastGen failed! with code: "+beastGenProcess.exitValue(), null);
		}
		filesToCleanup.add(workingDir+beastInput);
		log.info("BEAST input created.");
	}
	
	/**
	 * Runs BEAST on the input.xml file
	 * @param jobID
	 * @throws BeastException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void runBeast(String jobID) throws BeastException, IOException, InterruptedException {
		String input = jobID+INPUT_XML;
		final String currentDir = System.getProperty("user.dir");
		String beastDirPath = currentDir+"/ZooPhyJobs/";
		String beast = BEAST_SCRIPTS_DIR+"beast";
		log.info("Running BEAST...");
		File beastDir = new File(currentDir+"/ZooPhyJobs");
		ProcessBuilder builder;
		builder = new ProcessBuilder(beast, beastDirPath + input).directory(beastDir);
		builder.redirectOutput(Redirect.appendTo(logFile));
		builder.redirectError(Redirect.appendTo(logFile));
		BeastTailerListener listener = new BeastTailerListener();
		tail = new Tailer(logFile, listener);
		log.info("Starting Process: "+builder.command().toString());
		Process beastProcess = builder.start();
		PipelineManager.setProcess(job.getID(), beastProcess);
		tail.run();
		beastProcess.waitFor();
		tail.stop();
		if (beastProcess.exitValue() != 0) {
			tail.stop();
			log.log(Level.SEVERE, "BEAST failed! with code: "+beastProcess.exitValue());
			throw new BeastException("BEAST failed! with code: "+beastProcess.exitValue(), null);
		}
		if (wasKilled) {
			return;
		}
		File beastOutput = new File(currentDir+"/ZooPhyJobs/"+jobID+OUTPUT_TREES);
		if (!beastOutput.exists() || scanForBeastError()) {
			log.log(Level.SEVERE, "BEAST did not produce output! Trying it in always scaling mode...");
			builder = new ProcessBuilder(beast, "-beagle_scaling", "always", "-overwrite", beastDirPath + input).directory(beastDir);
			builder.redirectOutput(Redirect.appendTo(logFile));
			builder.redirectError(Redirect.appendTo(logFile));
			log.info("Starting Process: "+builder.command().toString());
			Process beastRerunProcess = builder.start();
			PipelineManager.setProcess(job.getID(), beastRerunProcess);
			tail.run();
			beastRerunProcess.waitFor();
			tail.stop();
			if (beastRerunProcess.exitValue() != 0) {
				tail.stop();
				log.log(Level.SEVERE, "Always-scaling BEAST failed! with code: "+beastProcess.exitValue());
				throw new BeastException("Always-scaling BEAST failed! with code: "+beastProcess.exitValue(), null);
			}
			beastOutput = new File(currentDir+"/ZooPhyJobs/"+jobID+OUTPUT_TREES);
			if (!beastOutput.exists()) {
				log.log(Level.SEVERE, "Always-scaling BEAST did not produce output!");
				throw new BeastException("Always-scaling BEAST did not produce output!", null);
			}
		}
		filesToCleanup.add(currentDir+"/ZooPhyJobs/"+jobID+OUTPUT_TREES);
		filesToCleanup.add(currentDir+"/ZooPhyJobs/"+jobID+"-aligned.log");
		filesToCleanup.add(currentDir+"/ZooPhyJobs/"+jobID+"-aligned.ops");
		filesToCleanup.add(currentDir+"/ZooPhyJobs/"+jobID+"-aligned.states.rates.log");
		log.info("BEAST finished.");
	}

	/**
	 * Runs the Tree Annotator to generate the final .tree file
	 * @param trees
	 * @return File path to resulting Tree File
	 * @throws BeastException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private String runTreeAnnotator(String trees) throws BeastException, IOException, InterruptedException {
		String tree = trees.substring(0, trees.indexOf("-aligned")) + RESULT_TREE;
		String baseDir = System.getProperty("user.dir") + "/ZooPhyJobs/";
		String treeannotator = BEAST_SCRIPTS_DIR+"treeannotator";
		log.info("Running Tree Annotator...");
		ProcessBuilder builder = new ProcessBuilder(treeannotator,"-burnin", "1000", baseDir+trees, baseDir+tree);
		builder.redirectOutput(Redirect.appendTo(logFile));
		builder.redirectError(Redirect.appendTo(logFile));
		log.info("Starting Process: "+builder.command().toString());
		Process treeAnnotatorProcess = builder.start();
		PipelineManager.setProcess(job.getID(), treeAnnotatorProcess);
		treeAnnotatorProcess.waitFor();
		if (treeAnnotatorProcess.exitValue() != 0) {
			log.log(Level.SEVERE, "Tree Annotator failed! with code: "+treeAnnotatorProcess.exitValue());
			throw new BeastException("Tree Annotator failed! with code: "+treeAnnotatorProcess.exitValue(), null);
		}
		log.info("Tree Annotator finished.");
		return baseDir+tree;
	}
	
	/**
	 * Appends a FigTree block to the .tree file
	 * @param treeFile
	 * @throws BeastException
	 */
	private void annotateTreeFile(String treeFile) throws BeastException {
		try {
			String ageOffset = "scale.offsetAge=";
			String youngestAge = findYougestAge(treeFile);
			FileWriter filewRiter = new FileWriter(treeFile, true);
			BufferedWriter bufferWriter = new BufferedWriter(filewRiter);
		    PrintWriter printer = new PrintWriter(bufferWriter);
		    Scanner scan = new Scanner(new File(FIGTREE_TEMPLATE));
		    while (scan.hasNext()) {
		    	String line = scan.nextLine();
		    	if (line.contains(ageOffset)) {
		    		line = line.substring(0,line.indexOf(ageOffset)+ageOffset.length())+youngestAge+";";
		    	}
		    	printer.println(line);
		    }
		    scan.close();
		    printer.close();
		    bufferWriter.close();
		    filewRiter.close();
		}
		catch (Exception e) {
			log.log(Level.SEVERE, "ERROR ADDING FIGTREE BLOCK: "+e.getMessage());
			throw new BeastException("ERROR ADDING FIGTREE BLOCK: "+e.getMessage(), null);
		}
	}
	
	/**
	 * Runs SpreaD3 to generate data visualization files 
	 * @throws BeastException
	 * @throws InterruptedException
	 * @throws IOException
	 */
	private void runSpread() throws BeastException, InterruptedException, IOException {
		String workingDir = System.getProperty("user.dir")+"/ZooPhyJobs/"+job.getID();
		log.info("Running SpreaD3 generator...");
		String coordinatesFile = workingDir+"-coords.txt";
		String treeFile = workingDir+".tree";
		String youngestDate = findYougestAge(treeFile);
		String spreadFile = workingDir+"-spread3.json";
		ProcessBuilder builder = new ProcessBuilder("java","-jar",SPREAD3,"-parse","-locations",coordinatesFile,"-header","false","-tree",treeFile,"-locationTrait","states","-intervals","10","-mrsd",youngestDate,"-geojson",WORLD_GEOJSON,"-output",spreadFile);
		builder.redirectOutput(Redirect.appendTo(logFile));
		builder.redirectError(Redirect.appendTo(logFile));
		log.info("Starting Process: "+builder.command().toString());
		Process spreadGenerationProcess = builder.start();
		PipelineManager.setProcess(job.getID(), spreadGenerationProcess);
		spreadGenerationProcess.waitFor();
		if (spreadGenerationProcess.exitValue() != 0) {
			log.log(Level.SEVERE, "SpreaD3 generation failed! with code: "+spreadGenerationProcess.exitValue());
			throw new BeastException("SpreaD3 generation failed! with code: "+spreadGenerationProcess.exitValue(), null);
		}
		log.info("SpreaD3 finished.");
		log.info("Running SpreaD3 render...");
		String renderPath = RENDER_DIR+"/"+job.getID();
		builder = new ProcessBuilder("java","-jar", SPREAD3,"-render","d3","-json",spreadFile,"-output",renderPath);
		builder.redirectOutput(Redirect.appendTo(logFile));
		builder.redirectError(Redirect.appendTo(logFile));
		log.info("Starting Process: "+builder.command().toString());
		Process spreadRenderProcess = builder.start();
		PipelineManager.setProcess(job.getID(), spreadRenderProcess);
		spreadRenderProcess.waitFor();
		if (spreadRenderProcess.exitValue() != 0) {
			log.log(Level.SEVERE, "SpreaD3 rendering failed! with code: "+spreadRenderProcess.exitValue());
			throw new BeastException("SpreaD3 rendering failed! with code: "+spreadRenderProcess.exitValue(), null);
		}
	}

	/**
	 * Deletes unwanted files after the BEAST process is finished
	 * @throws BeastException
	 */
	private void cleanupBeast() throws BeastException {
		log.info("Cleaning up BEAST...");
		try {
			Path fileToDelete;
			for (String filePath : filesToCleanup) {
				fileToDelete = Paths.get(filePath);
				Files.delete(fileToDelete);
				filesToCleanup.remove(filePath);
			}
			log.info("Cleanup complete.");
		}
		catch (Exception e) {
			log.log(Level.SEVERE, "Cleanup failed: "+e.getMessage());
		}
	}
	

	/**
	 * Finds the youngest Sequence date in the .tree file
	 * @param treeFile
	 * @return youngest Sequence date in the .tree file in decimal format
	 * @throws BeastException
	 */
	private String findYougestAge(String treeFile) throws BeastException {
		String youngestAge = "1996.0861";
		try {
			double minAge = 1920.0;
			double currAge = 0;
			Scanner scan = new Scanner(new File(treeFile));
			String line = scan.nextLine();
			while (!line.contains("Taxlabels")) {
				line = scan.nextLine();
			}
			line = scan.nextLine();
			while (line.contains("_")) {
				currAge = Double.parseDouble(line.split("_")[3]);
				if (currAge > minAge) {
					minAge = currAge;
					youngestAge = line.split("_")[3].trim();
				}
				line = scan.nextLine();
			}
			scan.close();
		}
		catch (Exception e) {
			log.log(Level.SEVERE, "ERROR SETTING FIGTREE START DATE: "+e.getMessage());
			throw new BeastException("ERROR SETTING FIGTREE START DATE: "+e.getMessage() , null);
		}
		return youngestAge;
	}
	
	/**
	 * Sends a time estimate to the user
	 * @param finishTime
	 * @param finalUpdate
	 */
	private void sendUpdate(String finishTime, boolean finalUpdate) {
		if (finalUpdate) {
			tail.stop();
		}
		try {
			if (finalUpdate || checkRateMatrix()) {
				mailer.sendUpdateEmail(finishTime, finalUpdate);
			}
			else {
				killBeast("Rate Matrix Error. Try reducing discrete states.");
			}
		}
		catch (Exception e) {
			if (rateTail != null) {
				rateTail.stop();
			}
			tail.stop();
			log.log(Level.SEVERE, "Error sending email: "+e.getMessage());
		}
	}
	
	/**
	 * Checks the jog log for a BEAST runtime error that may have still reported an exit code of 0
	 * @return True if the log contains a RuntimeException, False otherwise
	 * @throws FileNotFoundException
	 */
	private boolean scanForBeastError() throws FileNotFoundException {
		Scanner scan = new Scanner(logFile);
		String line;
		while (scan.hasNext()) {
			line = scan.nextLine();
			if (line.contains("java.lang.RuntimeException: An error was encounted. Terminating BEAST")) {
				scan.close();
				return true;
			}
		}
		scan.close();
		return false;
	}
	
	/**
	 * Checks the rates log file for obvious errors
	 * @return True if the rate matrix file is not obviously invalid, False otherwise
	 */
	private boolean checkRateMatrix() {
		try {
			File rateLog = new File(System.getProperty("user.dir")+"/ZooPhyJobs/"+job.getID()+"-aligned.states.rates.log");
			if (rateLog.exists()) {
				RateTailerListener rateListener = new RateTailerListener(); 
				rateTail = new Tailer(rateLog, rateListener);
				System.out.print("Starting rateTailer on "+rateLog.getAbsolutePath());
				rateTail.run();
			}
			else {
				throw new Exception("Rate Log does not exist!");
			}
			return true;
		}
		catch (Exception e) {
			if (rateTail != null) {
				rateTail.stop();
			}
			System.err.println("Error checking rate matrix: "+e.getMessage());
			return false;
		}
	}
	
	/**
	 * Kill the running Beast job
	 * @param reason - Reason for stopping the job
	 */
	private void killBeast(String reason) {
		if (tail != null) {
			tail.stop();
		}
		if (rateTail != null) {
			rateTail.stop();
		}
		mailer.sendFailureEmail(reason);
		wasKilled = true;
		beastProcess.destroy();
		PipelineManager.removeProcess(job.getID());
	}
	
	/**
	 * Tails the job log to screen BEAST output
	 * @author devdemetri
	 */
	private class BeastTailerListener extends TailerListenerAdapter {
	  boolean reached = false;
	  String checkPoint = "100000";
	  boolean finalUpdate = false;
	  
	  public void handle(String line) {
		  if (line != null && !(line.trim().isEmpty() || line.contains("INFO:") || line.contains("usa.ac.asu.dbi.diego.viralcontamination3"))) {
			  if (line.contains("hours/million states") && (line.trim().startsWith(checkPoint) || reached)) {
				  if (!PipelineManager.checkProcess(job.getID())) {
					  tail.stop();
					  killBeast("Process was already terminated.");
				  }
				  else {
					  System.out.println("\nTailer Reading: "+line);
					  reached = true;
					  try {
						  String[] beastColumns = line.split("\t");
						  if (beastColumns.length > 0) {
							  String progressRate = beastColumns[beastColumns.length-1].trim();
							  int estimatedHoursToGo;
							  if (finalUpdate) {
								  estimatedHoursToGo = (int)(Double.parseDouble(progressRate.substring(0, progressRate.indexOf('h')))*5.0)+1;
								  tail.stop();
							  }
							  else {
								  estimatedHoursToGo = (int)(Double.parseDouble(progressRate.substring(0, progressRate.indexOf('h')))*9.9)+1;
							  }
					  		  Date currentDate = new Date();
					  		  Calendar calendar = Calendar.getInstance();
					  		  calendar.setTime(currentDate);
					  		  calendar.add(Calendar.HOUR, estimatedHoursToGo);
					  		  String finishTime = calendar.getTime().toString();
					  		  sendUpdate(finishTime, finalUpdate);
					  		  reached = false;
					  		  checkPoint = "5000000";
					  		  finalUpdate = true;
					  	  }
					  }
					  catch (Exception e) {
						  log.log(Level.WARNING, "Failed to extract Beast time: "+e.getMessage());
					  }
				  }
			  }
			  else if (line.contains("java.lang.RuntimeException")) {
				  tail.stop();
			  }
		  }
	  }
	  
	}
	
	/**
	 * Listener for RateTailer
	 * @author devdemetri
	 */
	private class RateTailerListener extends TailerListenerAdapter {
		
		public void handle(String line) {
			boolean isFailing = true;
			final double standard = 1.0;
			if (line != null && line.startsWith("15000")) {
				rateTail.stop();
				if (!PipelineManager.checkProcess(job.getID())) {
					  killBeast("Process was already terminated.");
				}
				else {
					System.out.println("\nRateTailer read: "+line.trim());
					try {
						String[] row = line.trim().split("\t");
						for (int i = 1; i < row.length; i++) {
							double col = Double.parseDouble(row[i].trim());
							if (col != standard) {
								isFailing = false;
							}
						}
						if (isFailing) {
							killBeast("Rate Matrix Error. Try reducing discrete states.");
						}
					}
					catch (Exception e) {
						rateTail.stop();
						System.err.println("ERROR checking rate matrix file: "+e.getMessage());
						throw e;
					}
				}
			}
		}
		
	}
	
}