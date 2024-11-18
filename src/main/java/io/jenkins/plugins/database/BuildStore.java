package io.jenkins.plugins.database;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.UnprotectedRootAction;
import hudson.model.listeners.RunListener;
import org.apache.http.client.fluent.Request;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class BuildStore extends RunListener<Run<?, ?>> implements UnprotectedRootAction {

    private static final Logger LOGGER = Logger.getLogger(BuildStore.class.getName());
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/jenkins_builds";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "1234";

    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            LOGGER.severe("PostgreSQL JDBC Driver not found: " + e.getMessage());
        }
    }

    // Endpoint methods for the Jenkins plugin UI
    @Override
    public String getUrlName() { return "storeHtmlLog"; }
    @Override
    public String getDisplayName() { return "Store HTML Log in Database"; }
    @Override
    public String getIconFileName() { return null; }

    // Triggered when the build starts
    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        String processName = run.getParent().getParent().getDisplayName();
        String jobName = run.getParent().getDisplayName();
        int buildNumber = run.getNumber();
        String env = run.getParent().getParent().getDisplayName();
        Timestamp startTime = new Timestamp(run.getStartTimeInMillis());
        storeOrUpdateBuildData(processName, jobName, buildNumber, startTime, null, "UNKNOWN", env, null, null, null);
    }

    // Triggered when the build completes
    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        String processName = run.getParent().getParent().getDisplayName();
        String jobName = run.getParent().getDisplayName();
        int buildNumber = run.getNumber();
        String env = run.getParent().getParent().getFullName();
        String result = run.getResult() != null ? run.getResult().toString() : "UNKNOWN";
        Timestamp endTime = new Timestamp(System.currentTimeMillis());

        // Store log files for the latest build
        storeHtmlLogForLatestBuild(processName, env, jobName, buildNumber);

        storeOrUpdateBuildData(processName, jobName, buildNumber, null, endTime, result, env, null, null, null);
    }

    private static void storeOrUpdateBuildData(String processName, String jobName, int buildNumber, Timestamp startTime, Timestamp endTime,
                                               String status, String env, String fileLog, String fileReport, String fileOutput) {
        String sql = "INSERT INTO process (process_name, job_name, build_number, event_start_time, event_end_time, status, environment, file_log, file_report, file_output) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS xml)) " +
                "ON CONFLICT (environment, job_name, build_number) " +
                "DO UPDATE SET event_start_time = COALESCE(EXCLUDED.event_start_time, process.event_start_time), " +
                "event_end_time = COALESCE(EXCLUDED.event_end_time, process.event_end_time), " +
                "status = COALESCE(EXCLUDED.status, process.status), " +
                "file_log = COALESCE(EXCLUDED.file_log, process.file_log), " +
                "file_report = COALESCE(EXCLUDED.file_report, process.file_report), " +
                "file_output = COALESCE(CAST(EXCLUDED.file_output AS xml), process.file_output)";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, processName);
            stmt.setString(2, jobName);
            stmt.setInt(3, buildNumber);
            stmt.setTimestamp(4, startTime);
            stmt.setTimestamp(5, endTime);
            stmt.setString(6, status);
            stmt.setString(7, env);
            stmt.setString(8, fileLog);
            stmt.setString(9, fileReport);
            stmt.setString(10, fileOutput);
            stmt.executeUpdate();
            LOGGER.info("Build data stored or updated successfully in PostgreSQL.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to store or update build data in PostgreSQL: " + e.getMessage(), e);
        }
    }

    // Fetch and store log files for the latest build
    private static void storeHtmlLogForLatestBuild(String processName, String env, String jobName, int buildNumber) {
        String[] fileTypes = {"log.html", "report.html", "output.xml"};
        String[] fileTypeColumns = {"file_log", "file_report", "file_output"};

        for (int i = 0; i < fileTypes.length; i++) {
            String fileUrl = String.format("http://localhost:8080/openLogFile?filePath=/jobs/%s/jobs/%s/builds/%d/robot-plugin/%s", env, jobName, buildNumber, fileTypes[i]);
            try {
                String content = Request.Get(fileUrl).execute().returnContent().asString();
                if (content != null) {
                    storeOrUpdateBuildData(processName, jobName, buildNumber, null, null, null, env, i == 0 ? content : null, i == 1 ? content : null, i == 2 ? content : null);
                }
            } catch (Exception e) {
                System.err.println("Error fetching or storing content for " + fileTypes[i] + ": " + e.getMessage());
            }
        }
    }
}
