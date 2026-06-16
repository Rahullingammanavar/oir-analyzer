import com.olympus.oir.parser.OirParser;
import com.olympus.oir.model.ParsedOirFile;
import java.io.File;

public class TestRegex {
    public static void main(String[] args) throws Exception {
        File f = new File("D:\\phase-01\\assignment1-phase1.oir");
        OirParser parser = new OirParser(f);
        ParsedOirFile parsed = parser.parse();
        
        String xml = parsed.getImagePropertiesXml().orElse("");
        
        java.util.regex.Pattern pChannel = java.util.regex.Pattern.compile("<lsmimage:channel\\s+[^>]*\\bid=\"([^\"]+)\"[^>]*>.*?<commonimage:name>([^<]+)</commonimage:name>.*?</lsmimage:channel>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher mChannel = pChannel.matcher(xml);
        int count = 0;
        while(mChannel.find()) {
            System.out.println(mChannel.group(1) + " -> " + mChannel.group(2));
            count++;
        }
        System.out.println("Total mapped: " + count);
    }
}
