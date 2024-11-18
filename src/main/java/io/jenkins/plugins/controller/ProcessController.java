package io.jenkins.plugins.controller;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import org.jenkinsci.Symbol;
import org.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

@Symbol("latest-build-data")
@Extension
public class ProcessController implements UnprotectedRootAction {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/jenkins_builds";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "1234";

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "Latest Build Data API";
    }

    @Override
    public String getUrlName() {
        return "latest-build-data";
    }

    // doData will now return build data and URLs for displaying content
    public void doData(StaplerRequest request, StaplerResponse response) {
        response.setContentType("application/json");

        try (PrintWriter out = response.getWriter()) {
            Map<String, Object> result = getLatestBuildData();

            // Add display URLs for report.html, output.xml, log.html using numbers 1, 2, 3
            String env = (String) result.get("env");
            String jobName = (String) result.get("job_name");
            String buildNumber = String.valueOf(result.get("build_number"));

            if (env != null && jobName != null) {
                result.put("reportHtmlUrl", getDisplayUrl(env, jobName, buildNumber, 2)); // Report HTML -> 2
                result.put("outputXmlUrl", getDisplayUrl(env, jobName, buildNumber, 3)); // Output XML -> 3
                result.put("logHtmlUrl", getDisplayUrl(env, jobName, buildNumber, 1)); // Log HTML -> 1
            }

            JSONObject jsonResponse = new JSONObject(result);

            // Write the JSON response to the client
            out.write(jsonResponse.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Retrieve the latest build data from the database
    private Map<String, Object> getLatestBuildData() {
        Map<String, Object> buildData = new LinkedHashMap<>();
        String query = "SELECT job_name, build_number, event_start_time, event_end_time, result, env " +
                "FROM process2 ORDER BY event_start_time DESC LIMIT 1";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                buildData.put("job_name", rs.getString("job_name"));
                buildData.put("build_number", rs.getInt("build_number"));
                buildData.put("event_start_time", rs.getTimestamp("event_start_time"));
                buildData.put("event_end_time", rs.getTimestamp("event_end_time"));
                buildData.put("result", rs.getString("result"));
                buildData.put("env", rs.getString("env"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return buildData;
    }

    // Get the URL for displaying the file content directly (e.g., report.html, output.xml, log.html)
    private String getDisplayUrl(String env, String jobName, String buildNumber, int file) {
        return String.format("/jenkins/latest-build-data/showFileContent?env=%s&jobName=%s&buildNumber=%s&file=%d",
                env, jobName, buildNumber, file);
    }

    // Endpoint to show file content (instead of downloading)
    public void doShowFileContent(StaplerRequest request, StaplerResponse response,
                                  @org.kohsuke.stapler.QueryParameter String env,
                                  @org.kohsuke.stapler.QueryParameter String jobName,
                                  @org.kohsuke.stapler.QueryParameter String buildNumber,
                                  @org.kohsuke.stapler.QueryParameter int file) {

        String content = getFileContent(env, jobName, Integer.parseInt(buildNumber), file);

        if (content != null) {
            // Set appropriate content type based on file type (1 -> log.html, 2 -> report.html, 3 -> output.xml)
            if (file == 3) {
                response.setContentType("application/xml");
            } else if (file == 2 || file == 1) {
                response.setContentType("text/html");
            } else {
                response.setContentType("text/plain");
            }

            // Write content to response
            try (PrintWriter out = response.getWriter()) {
                out.write(content);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            // If content is not found, return 404
            response.setStatus(404);
        }
    }

    // Retrieve file content from the database based on env, jobName, buildNumber, and file (1, 2, or 3)
    private String getFileContent(String env, String jobName, int buildNumber, int file) {
        String content = null;
        String query = "";

        switch (file) {
            case 1: // Log HTML
                query = "SELECT log_html FROM process_files WHERE enviroment = ? AND process = ? AND build_number = ?";
                break;
            case 2: // Report HTML
                query = "SELECT report_html FROM process_files WHERE enviroment = ? AND process = ? AND build_number = ?";
                break;
            case 3: // Output XML
                query = "SELECT output_xml FROM process_files WHERE enviroment = ? AND process = ? AND build_number = ?";
                break;
            default:
                return null; // Invalid file type
        }

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, env);
            stmt.setString(2, jobName);
            stmt.setInt(3, buildNumber);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                if (file == 3) {
                    content = rs.getString("output_xml");
                } else if (file == 2) {
                    content = rs.getString("report_html");
                } else if (file == 1) {
                    content = rs.getString("log_html");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return content;
    }
}
