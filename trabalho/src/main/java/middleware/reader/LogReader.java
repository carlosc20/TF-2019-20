package middleware.reader;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogReader {

    private String logPath;
    private List<String> queries;
    private List<Long> timeStamps;

    public LogReader(String logPath){
        this.logPath = logPath;
        this.queries = null;
        this.timeStamps = new LinkedList<>();
    }

    public int size() throws Exception{
        String fileStr = getFileString();
        getQueriesFromString(fileStr);
        return queries.size();
    }

    public Collection<String> getQueries() throws Exception {
        String fileStr = getFileString();
        getQueriesFromString(fileStr);
        return queries;
    }

    private String getFileString() throws Exception{
        File log = new File(logPath);
        FileInputStream inputStream = new FileInputStream(log);
        return new String(inputStream.readAllBytes());
    }

    public ArrayList<String> getQueries(int lowerBound, int upperBound) throws Exception{
        System.out.println(lowerBound + " -> " + upperBound + " " + logPath);
        String fileString = getFileString();
        if(lowerBound > upperBound) {
            return new ArrayList<>(0);
        }
        ArrayList<String> res = new ArrayList<>(upperBound-lowerBound);
        for(int i = lowerBound; i < upperBound; i++){
            res.add(queries.get(i));
        }
        return res;
    }

    public ArrayList<String> getQueries(int lowerBound) throws Exception {
        return getQueries(lowerBound, size());
    }

    private void getQueriesFromString(String filestr){
        if(queries == null){
            queries = new ArrayList<>();
            //String splitRegex = "\\d+-\\d+-\\d+ \\d+:\\d+:\\d+\\.\\d+ \\d+";
            String splitRegex = "\n"; //(?=DROP|SET|INSERT|DELETE|CREATE|REPLACE|COMMIT|ROLLBACK)"; //"\n(?=\\d{4}-\\d+-\\d+)";
            Pattern logLine = Pattern.compile("(/\\*.*\\*/)?((.|[\n\r])*)(/\\*T\\d+)?");//"\\d+-\\d+-\\d+ \\d+:\\d+:\\d+[.]\\d+ \\d ((.|\n)*)");
            String[] split = filestr.split(splitRegex);
            for(String log : split){
                Matcher matcher = logLine.matcher(log);
                if(matcher.find()){
                    String query = matcher.group(2).replaceAll("(\\\\u000a)|(\\/\\*.*\\*\\/)", "");
                    String timeStampStr = matcher.group(3);
                    timeStampStr = timeStampStr == null ? "-1" : timeStampStr.substring(4);
                    long timeStamp = Long.parseLong(timeStampStr);
                    System.out.println("Query: " + query);
                    timeStamps.add(timeStamp);
                    queries.add(query);
                } else {
                    System.out.println("Log " + log + " couldn't be parsed");
                }
            }
        }
    }

    public static void main(String[] args)  throws  Exception{
        LogReader logReader = new LogReader("db/Server1.file");
        logReader.getQueries(18).forEach(System.out::println);
    }

    public void resetQueries() {
        this.queries = null;
    }

    public String getPath() {
        return this.logPath;
    }

    public void putTimestamp(long timestamp) throws IOException {
        putTimeStamp(Long.toString(timestamp));
    }

    public void putTimeStamp(String timestamp) throws IOException {
        FileOutputStream log = new FileOutputStream(new File(logPath), true);
        log.write("/*T".getBytes());
        log.write(timestamp.getBytes());
        log.write("*/".getBytes());
        log.close();
    }

    public String getAll() throws Exception{
        Path path = FileSystems.getDefault().getPath(logPath);
        return new String(Files.readAllBytes(path));
    }

    public ArrayList<String> getLogsAfter(long timeStamp) throws Exception{
        getQueries();
        int i = 0;
        for(; i < timeStamps.size() && timeStamps.get(i) <= timeStamp; i++);
        return new ArrayList<>(this.queries.subList(i, timeStamps.size()));
    }
}
