import java.sql.Connection;
import java.sql.DriverManager;

public class DBConnection {
    private static final String URL = "jdbc:mysql://localhost:3306/campus_grievance";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "1234"; // <-- अपना MySQL पासवर्ड यहाँ डालें

    public static Connection getConnection() {
        Connection con = null;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            con = DriverManager.getConnection(URL, USERNAME, PASSWORD);
        } catch (Exception e) {
            System.out.println("❌ Database Connection Failed!");
            e.printStackTrace();
        }
        return con;
    }
    public static void main(String[] args) {
        Connection c = getConnection();
        if (c != null) {
            System.out.println(">>> SUCCESS: DATABASE CONNECTED! <<<");
        } else {
            System.out.println(">>> FAILED: CHECK PASSWORD! <<<");
        }
    }
}