import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;

public class AppServer {
    private static final Map<String, Integer> activeSessions = new HashMap<>();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        // Root redirect to /login
        server.createContext("/", exchange -> {
            if ("/".equals(exchange.getRequestURI().getPath())) {
                exchange.getResponseHeaders().set("Location", "/login");
                exchange.sendResponseHeaders(302, -1);
            } else {
                sendResponse(exchange, 404, "404 Not Found");
            }
        });

        // 1. Login & Register Page
        server.createContext("/login", exchange -> {
            File file = new File("login.html");
            if (!file.exists()) {
                sendResponse(exchange, 404, "<h1>login.html not found in folder!</h1>");
                return;
            }
            byte[] response = Files.readAllBytes(file.toPath());
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        });

        // 2. Register Route
        server.createContext("/auth/register", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> params = getPostParams(exchange);
                String name = params.getOrDefault("name", "").trim();
                String email = params.getOrDefault("email", "").trim();
                String pass = params.getOrDefault("password", "").trim();

                System.out.println("Registration Attempt: Name=" + name + ", Email=" + email);

                try (Connection con = DBConnection.getConnection()) {
                    if (con == null) {
                        System.out.println("ERROR: DBConnection is NULL! Check root password in DBConnection.java.");
                    } else {
                        String query = "INSERT INTO users (name, email, password, role) VALUES (?, ?, ?, 'STUDENT')";
                        PreparedStatement ps = con.prepareStatement(query);
                        ps.setString(1, name);
                        ps.setString(2, email);
                        ps.setString(3, pass);
                        int affected = ps.executeUpdate();
                        System.out.println("✅ User Registered Successfully! (Rows: " + affected + ")");
                    }
                } catch (Exception e) {
                    System.out.println("❌ Registration Failed with SQL Exception:");
                    e.printStackTrace();
                }

                // Redirect to login tab
                exchange.getResponseHeaders().set("Location", "/login");
                exchange.sendResponseHeaders(302, -1);
            }
        });

        // 3. Login Route
        server.createContext("/auth/login", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> params = getPostParams(exchange);
                String email = params.getOrDefault("email", "").trim();
                String pass = params.getOrDefault("password", "").trim();
                int loggedUserId = -1;

                System.out.println("Login Attempt for: " + email);

                try (Connection con = DBConnection.getConnection()) {
                    String query = "SELECT user_id FROM users WHERE email = ? AND password = ?";
                    PreparedStatement ps = con.prepareStatement(query);
                    ps.setString(1, email);
                    ps.setString(2, pass);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        loggedUserId = rs.getInt("user_id");
                        System.out.println("✅ User Authenticated! User ID: " + loggedUserId);
                    } else {
                        System.out.println("❌ Invalid credentials for: " + email);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (loggedUserId != -1) {
                    String sessionId = UUID.randomUUID().toString();
                    activeSessions.put(sessionId, loggedUserId);
                    exchange.getResponseHeaders().add("Set-Cookie", "auth_session=" + sessionId + "; Path=/; HttpOnly");
                    exchange.getResponseHeaders().set("Location", "/student");
                    exchange.sendResponseHeaders(302, -1);
                } else {
                    sendResponse(exchange, 401, "<h3 style='font-family:sans-serif; text-align:center; margin-top:50px;'>Invalid Email or Password! <br><br><a href='/login'>Back to Login</a></h3>");
                }
            }
        });

        // 4. Logout Route
        server.createContext("/logout", exchange -> {
            String sessionId = getCookie(exchange, "auth_session");
            if (sessionId != null) activeSessions.remove(sessionId);
            exchange.getResponseHeaders().add("Set-Cookie", "auth_session=; Path=/; Max-Age=0");
            exchange.getResponseHeaders().set("Location", "/login");
            exchange.sendResponseHeaders(302, -1);
        });

        // 5. Student Dashboard Route
        server.createContext("/student", exchange -> {
            Integer userId = getAuthenticatedUserId(exchange);
            if (userId == null) {
                exchange.getResponseHeaders().set("Location", "/login");
                exchange.sendResponseHeaders(302, -1);
                return;
            }

            File file = new File("student_dashboard.html");
            if (!file.exists()) {
                sendResponse(exchange, 404, "<h1>student_dashboard.html not found!</h1>");
                return;
            }

            StringBuilder studentRows = new StringBuilder();
            try (Connection con = DBConnection.getConnection()) {
                String q = "SELECT ticket_id, category, subject, description, is_anonymous, status FROM complaints WHERE user_id = ? ORDER BY ticket_id DESC LIMIT 1";
                PreparedStatement ps = con.prepareStatement(q);
                ps.setInt(1, userId);
                ResultSet rs = ps.executeQuery();

                if (rs.next()) {
                    int ticketId = rs.getInt("ticket_id");
                    String category = rs.getString("category");
                    String subject = rs.getString("subject");
                    String desc = rs.getString("description");
                    boolean isAnon = rs.getBoolean("is_anonymous");
                    String status = rs.getString("status");

                    String badgeClass = "bg-warning text-dark";
                    if ("In-Progress".equalsIgnoreCase(status) || "In Progress".equalsIgnoreCase(status)) {
                        badgeClass = "bg-info text-dark";
                    } else if ("Resolved".equalsIgnoreCase(status)) {
                        badgeClass = "bg-success text-white";
                    }

                    String privacyTag = isAnon 
                        ? "<span class='badge bg-dark'>🕵️ Anonymous</span>" 
                        : "<span class='badge bg-light text-dark border'>Public</span>";

                    studentRows.append("<tr>")
                            .append("<td><strong>#").append(ticketId).append("</strong></td>")
                            .append("<td><span class='badge bg-secondary'>").append(category).append("</span></td>")
                            .append("<td>").append(subject).append("</td>")
                            .append("<td>").append(desc).append("</td>")
                            .append("<td>").append(privacyTag).append("</td>")
                            .append("<td><span class='badge ").append(badgeClass).append("'>").append(status).append("</span></td>")
                            .append("</tr>");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (studentRows.length() == 0) {
                studentRows.append("<tr><td colspan='6' class='text-center text-muted py-3'>No complaints registered yet.</td></tr>");
            }

            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            html = html.replace("<!--STUDENT_COMPLAINTS_ROWS-->", studentRows.toString());

            byte[] response = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        });

        // 6. Submit Complaint Route
        server.createContext("/submit-complaint", exchange -> {
            Integer userId = getAuthenticatedUserId(exchange);
            if (userId == null) {
                exchange.getResponseHeaders().set("Location", "/login");
                exchange.sendResponseHeaders(302, -1);
                return;
            }

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> params = getPostParams(exchange);
                boolean isAnonymous = "true".equalsIgnoreCase(params.get("is_anonymous"));

                try (Connection con = DBConnection.getConnection()) {
                    String query = "INSERT INTO complaints (user_id, category, subject, description, is_anonymous, status) VALUES (?, ?, ?, ?, ?, 'Pending')";
                    PreparedStatement ps = con.prepareStatement(query);
                    ps.setInt(1, userId);
                    ps.setString(2, params.getOrDefault("category", "General"));
                    ps.setString(3, params.getOrDefault("subject", "No Subject"));
                    ps.setString(4, params.getOrDefault("description", "No Description"));
                    ps.setBoolean(5, isAnonymous);
                    ps.executeUpdate();
                    System.out.println("Complaint added successfully (Anonymous: " + isAnonymous + ")");
                } catch (Exception e) {
                    e.printStackTrace();
                }

                exchange.getResponseHeaders().set("Location", "/student");
                exchange.sendResponseHeaders(302, -1);
            }
        });

        // 7. Dynamic Admin Dashboard Route
        server.createContext("/admin", exchange -> {
            File file = new File("admin_dashboard.html");
            if (!file.exists()) {
                sendResponse(exchange, 404, "<h1>admin_dashboard.html not found!</h1>");
                return;
            }

            StringBuilder rowsHtml = new StringBuilder();
            try (Connection con = DBConnection.getConnection();
                 Statement stmt = con.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT c.ticket_id, c.category, c.subject, c.description, c.status, c.is_anonymous, u.name, u.email FROM complaints c LEFT JOIN users u ON c.user_id = u.user_id ORDER BY c.ticket_id DESC")) {

                while (rs.next()) {
                    int ticketId = rs.getInt("ticket_id");
                    String category = rs.getString("category");
                    String subject = rs.getString("subject");
                    String desc = rs.getString("description");
                    String status = rs.getString("status");
                    boolean isAnonymous = rs.getBoolean("is_anonymous");

                    String studentDisplay;
                    if (isAnonymous) {
                        studentDisplay = "<span class='badge bg-danger text-white mb-1'>🕵️ Identity Hidden</span><br><small class='text-muted'>Anonymous Student</small>";
                    } else {
                        String name = rs.getString("name") != null ? rs.getString("name") : "Student";
                        String email = rs.getString("email") != null ? rs.getString("email") : "";
                        studentDisplay = "<strong>" + name + "</strong><br><small class='text-muted'>" + email + "</small>";
                    }

                    String badgeClass = "bg-warning text-dark";
                    if ("In-Progress".equalsIgnoreCase(status) || "In Progress".equalsIgnoreCase(status)) {
                        badgeClass = "bg-info text-dark";
                    } else if ("Resolved".equalsIgnoreCase(status)) {
                        badgeClass = "bg-success text-white";
                    }

                    rowsHtml.append("<tr>")
                            .append("<td><strong>#").append(ticketId).append("</strong></td>")
                            .append("<td>").append(studentDisplay).append("</td>")
                            .append("<td><span class='badge bg-secondary'>").append(category).append("</span></td>")
                            .append("<td>").append(subject).append("</td>")
                            .append("<td>").append(desc).append("</td>")
                            .append("<td><span class='badge ").append(badgeClass).append("'>").append(status).append("</span></td>")
                            .append("<td>")
                            .append("<form action='/update-status' method='POST' class='d-flex gap-2'>")
                            .append("<input type='hidden' name='ticket_id' value='").append(ticketId).append("'>")
                            .append("<select name='status' class='form-select form-select-sm' style='width: 135px;'>")
                            .append("<option value='Pending'").append("Pending".equalsIgnoreCase(status) ? " selected" : "").append(">Pending</option>")
                            .append("<option value='In-Progress'").append(("In-Progress".equalsIgnoreCase(status) || "In Progress".equalsIgnoreCase(status)) ? " selected" : "").append(">In Progress</option>")
                            .append("<option value='Resolved'").append("Resolved".equalsIgnoreCase(status) ? " selected" : "").append(">Resolved</option>")
                            .append("</select>")
                            .append("<button type='submit' class='btn btn-sm btn-dark'>Save</button>")
                            .append("</form>")
                            .append("</td>")
                            .append("</tr>");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            html = html.replace("<!--COMPLAINTS_TABLE_ROWS-->", rowsHtml.toString());

            byte[] response = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        });

        // 8. Update Complaint Status Route
        server.createContext("/update-status", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> params = getPostParams(exchange);

                try (Connection con = DBConnection.getConnection()) {
                    String query = "UPDATE complaints SET status = ? WHERE ticket_id = ?";
                    PreparedStatement ps = con.prepareStatement(query);
                    ps.setString(1, params.get("status"));
                    ps.setInt(2, Integer.parseInt(params.get("ticket_id")));
                    ps.executeUpdate();
                    System.out.println("Status updated to " + params.get("status") + " for Ticket #" + params.get("ticket_id"));
                } catch (Exception e) {
                    e.printStackTrace();
                }

                exchange.getResponseHeaders().set("Location", "/admin");
                exchange.sendResponseHeaders(302, -1);
            }
        });

        System.out.println(">>> Server Live: http://localhost:8080/login");
        System.out.println(">>> Admin Portal: http://localhost:8080/admin");
        server.start();
    }

    private static Integer getAuthenticatedUserId(HttpExchange exchange) {
        String sessionId = getCookie(exchange, "auth_session");
        if (sessionId != null && activeSessions.containsKey(sessionId)) {
            return activeSessions.get(sessionId);
        }
        return null;
    }

    private static String getCookie(HttpExchange exchange, String cookieName) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies != null) {
            for (String header : cookies) {
                for (String c : header.split(";")) {
                    String[] pair = c.trim().split("=");
                    if (pair.length == 2 && pair[0].equals(cookieName)) {
                        return pair[1];
                    }
                }
            }
        }
        return null;
    }

    private static Map<String, String> getPostParams(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        String formData = baos.toString(StandardCharsets.UTF_8.name());
        Map<String, String> map = new HashMap<>();
        if (formData.isEmpty()) return map;

        for (String pair : formData.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
            } else if (kv.length == 1) {
                map.put(URLDecoder.decode(kv[0], "UTF-8"), "");
            }
        }
        return map;
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }
}