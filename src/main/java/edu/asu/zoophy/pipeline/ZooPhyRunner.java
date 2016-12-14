package edu.asu.zoophy.pipeline;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Responsible for running ZooPhy jobs
 * @author devdemetri
 */
public class ZooPhyRunner {
	
	private ZooPhyJob job;
	private ZooPhyMailer mailer;
	
	/**
	 * Map for tracking running jobs
	 * Key - generated JobID
	 * Value - server PID
	 */
	protected static Map<String, Integer> ids = new ConcurrentHashMap<String, Integer>();
	
	public ZooPhyRunner(String replyEmail) {
		job = new ZooPhyJob(generateID(),null,replyEmail);
	}

	public ZooPhyRunner(String replyEmail, String jobName) {
		job = new ZooPhyJob(generateID(),jobName,replyEmail);
	}
	
	/**
	 * Runs the ZooPhy pipeline on the given Accessions
	 * @param accessions
	 * @throws PipelineException
	 */
	public void runZooPhy(List<String> accessions) throws PipelineException {
		try {
			mailer = new ZooPhyMailer(job);
			mailer.sendStartEmail();
			//TODO: add rest of pipeline
			// mailer.sendSuccessEmail();
		}
		catch (PipelineException pe) {
			mailer.sendFailureEmail(pe.getUserMessage());
		}
		catch (Exception e) {
			mailer.sendFailureEmail("Server Error");
		}
	}
	
	/**
	 * Generates a UUID to be used as a jobID
	 * @return Unused UUID
	 */
	private static String generateID() {
		String id  = java.util.UUID.randomUUID().toString();
		while (ids.get(id) != null) {
			id  = java.util.UUID.randomUUID().toString();
		}
		ids.put(id, null);
		return id;
	}
	
	/**
	 * Kills the given ZooPhy Job
	 * @param jobID - ID of ZooPhy job to kill
	 * @throws PipelineException if the job does not exist
	 */
	public static void killJob(String jobID) throws PipelineException {
		try {
			Integer pid = ids.get(jobID);
			if (pid == null || pid < 100) {
				throw new PipelineException("ERROR! Tried to kill non-existent job: "+jobID, "Job Does Not Exist!");
			}
			ProcessBuilder builder = new ProcessBuilder("kill", "-9", pid.toString());
			Process process = builder.start();
			process.waitFor();
			if (process.exitValue() != 0) {
				throw new PipelineException("ERROR! Could not kill job: "+jobID+" with code: "+process.exitValue(), "Could Not Kill Job!");
			}
		}
		catch (Exception e) {
			throw new PipelineException("ERROR! Could not kill job: "+jobID+" : "+e.getMessage(), "Could Not Kill Job!");
		}
	}

}
