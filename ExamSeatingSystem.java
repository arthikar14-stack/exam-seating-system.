import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ExamSeatingSystem {

    // ---------- Student class ----------
    static class Student {
        private final String regNo, name, dept, subject;

        Student(String regNo, String name, String dept, String subject) {
            this.regNo = regNo;
            this.name = name;
            this.dept = dept;
            this.subject = subject;
        }
        public String getRegNo()   { return regNo; }
        public String getName()    { return name; }
        public String getDept()    { return dept; }
        public String getSubject() { return subject; }
    }

    // ---------- Hall class ----------
    static class Hall {
        private final String name;
        private final int rows, cols;
        private final Student[][] seats;

        Hall(String name, int rows, int cols) {
            this.name = name;
            this.rows = rows;
            this.cols = cols;
            this.seats = new Student[rows][cols];
        }
        public String getName() { return name; }
        public int getRows()    { return rows; }
        public int getCols()    { return cols; }
        public int capacity()   { return rows * cols; }
        public Student getSeat(int r, int c)       { return seats[r][c]; }
        public void setSeat(int r, int c, Student s) { seats[r][c] = s; }
    }

    // ---------- User defined exception ----------
    static class SeatingException extends Exception {
        SeatingException(String message) {
            super(message);
        }
    }

    // ---------- Interface ----------
    interface Allocator {
        void allocate(List<Student> students, List<Hall> halls) throws SeatingException;
    }

    // ---------- Allocation logic ----------
    static class SubjectSeparatingAllocator implements Allocator {

        @Override
        public void allocate(List<Student> students, List<Hall> halls) throws SeatingException {
            // 1. Validate duplicates
            Set<String> seen = new HashSet<>();
            for (Student s : students) {
                if (!seen.add(s.getRegNo())) {
                    throw new SeatingException("Duplicate register number: " + s.getRegNo());
                }
            }
            // 2. Check capacity
            int capacity = 0;
            for (Hall h : halls) {
                capacity += h.capacity();
            }
            if (students.size() > capacity) {
                throw new SeatingException("Not enough seats: students = "
                        + students.size() + ", capacity = " + capacity);
            }
            // 3. Group students by subject
            Map<String, Deque<Student>> groups = new HashMap<>();
            for (Student s : students) {
                groups.computeIfAbsent(s.getSubject(), k -> new ArrayDeque<>()).add(s);
            }
            int remaining = students.size();

            // 4. Fill seats row by row
            for (Hall h : halls) {
                for (int r = 0; r < h.getRows(); r++) {
                    for (int c = 0; c < h.getCols(); c++) {
                        if (remaining == 0) {
                            return;
                        }
                        String left = (c > 0 && h.getSeat(r, c - 1) != null)
                                ? h.getSeat(r, c - 1).getSubject() : null;
                        String front = (r > 0 && h.getSeat(r - 1, c) != null)
                                ? h.getSeat(r - 1, c).getSubject() : null;

                        String chosen = null;
                        int best = 0;
                        for (Map.Entry<String, Deque<Student>> e : groups.entrySet()) {
                            int size = e.getValue().size();
                            if (size > best && !e.getKey().equals(left)
                                    && !e.getKey().equals(front)) {
                                best = size;
                                chosen = e.getKey();
                            }
                        }
                        if (chosen == null) {          // no safe subject, take any
                            for (Map.Entry<String, Deque<Student>> e : groups.entrySet()) {
                                if (!e.getValue().isEmpty()) {
                                    chosen = e.getKey();
                                    break;
                                }
                            }
                        }
                        h.setSeat(r, c, groups.get(chosen).poll());
                        remaining--;
                    }
                }
            }
        }
    }

    // ---------- File handling ----------
    static List<Student> loadStudents(String file) throws IOException, SeatingException {
        List<Student> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine();               // skip header
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] p = line.split(",");
                if (p.length < 4) {
                    throw new SeatingException("Invalid student record: " + line);
                }
                list.add(new Student(p[0].trim(), p[1].trim(), p[2].trim(), p[3].trim()));
            }
        }
        return list;
    }

    static List<Hall> loadHalls(String file) throws IOException, SeatingException {
        List<Hall> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine();               // skip header
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] p = line.split(",");
                if (p.length < 3) {
                    throw new SeatingException("Invalid hall record: " + line);
                }
                list.add(new Hall(p[0].trim(),
                        Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())));
            }
        }
        return list;
    }

    static void saveReport(List<Hall> halls, String file) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            for (Hall h : halls) {
                pw.println("===== " + h.getName() + " =====");
                for (int r = 0; r < h.getRows(); r++) {
                    StringBuilder sb = new StringBuilder();
                    for (int c = 0; c < h.getCols(); c++) {
                        Student s = h.getSeat(r, c);
                        sb.append(s == null ? "------------" : s.getRegNo());
                        sb.append(" | ");
                    }
                    pw.println(sb);
                }
                pw.println();
            }
        }
    }

    // ---------- Web helpers ----------
    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> m = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null) {
            return m;
        }
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                  kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    static String hallTable(Hall h, String highlight) {
        StringBuilder sb = new StringBuilder("<h2>" + esc(h.getName()) + "</h2><table>");
        for (int r = 0; r < h.getRows(); r++) {
            sb.append("<tr>");
            for (int c = 0; c < h.getCols(); c++) {
                Student s = h.getSeat(r, c);
                if (s == null) {
                    sb.append("<td class='empty'>Empty</td>");
                } else {
                    String cls = s.getRegNo().equals(highlight) ? " class='me'" : "";
                    sb.append("<td" + cls + ">" + esc(s.getRegNo())
                            + "<br><small>" + esc(s.getName()) + " - "
                            + esc(s.getSubject()) + "</small></td>");
                }
            }
            sb.append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    static String page(String body) {
        return "<html><head><title>Exam Seating</title><style>"
                + "body{font-family:Arial;margin:30px}"
                + "table{border-collapse:collapse;margin-bottom:20px}"
                + "td{border:1px solid #444;padding:10px;text-align:center;min-width:130px}"
                + ".me{background:#ffe08a;font-weight:bold}.empty{color:#999}"
                + "</style></head><body><h1>Smart Exam Seating System</h1>"
                + "<form action='/search'>Register No: <input name='reg'> "
                + "<button>Find My Seat</button></form><a href='/'>All Halls</a><hr>"
                + body + "</body></html>";
    }

    static void send(HttpExchange ex, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------- Main ----------
    public static void main(String[] args) {
        try {
            List<Student> students = loadStudents("students.csv");
            List<Hall> halls = loadHalls("halls.csv");

            Allocator allocator = new SubjectSeparatingAllocator();
            allocator.allocate(students, halls);
            saveReport(halls, "seating_report.txt");

            int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                Map<String, String> q = query(ex.getRequestURI());
                StringBuilder body = new StringBuilder();

                if (path.equals("/search")) {
                    String reg = q.getOrDefault("reg", "").trim();
                    boolean found = false;
                    for (Hall h : halls) {
                        for (int r = 0; r < h.getRows(); r++) {
                            for (int c = 0; c < h.getCols(); c++) {
                                Student s = h.getSeat(r, c);
                                if (s != null && s.getRegNo().equals(reg)) {
                                    body.append("<p><b>" + esc(s.getName()) + "</b> : "
                                            + esc(h.getName()) + ", Row " + (r + 1)
                                            + ", Seat " + (c + 1) + "</p>");
                                    body.append(hallTable(h, reg));
                                    found = true;
                                }
                            }
                        }
                    }
                    if (!found) {
                        body.append("<p>No seat found for register number " + esc(reg) + "</p>");
                    }
                } else {
                    for (Hall h : halls) {
                        body.append(hallTable(h, ""));
                    }
                }
                send(ex, page(body.toString()));
            });
            server.start();
            System.out.println("Seating done. Open http://localhost:" + port + " in your browser.");

        } catch (SeatingException e) {
            System.out.println("Allocation error: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }
}
