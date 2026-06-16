import com.olympus.oir.parser.OirParser;
import com.olympus.oir.model.ParsedOirFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import java.io.File;
import java.io.StringReader;
import org.xml.sax.InputSource;

public class TestDimensions {
    public static void main(String[] args) throws Exception {
        File f = new File("D:\\phase-01\\assignment1-phase1.oir");
        OirParser parser = new OirParser(f);
        ParsedOirFile parsed = parser.parse();
        
        String xml = parsed.getImagePropertiesXml().orElse("");
        if (xml.startsWith("\uFEFF")) xml = xml.substring(1);
        xml = xml.stripLeading();
        
        System.out.println("XML prefix: " + xml.substring(0, 300));
        
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("<lsmimage:channel\\s+([^>]*)>(.*?)</lsmimage:channel>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(xml);
        while(m.find()) {
            System.out.println("Match Attributes: " + m.group(1));
            System.out.println("Match Inner: " + m.group(2));
        }
    }
}
