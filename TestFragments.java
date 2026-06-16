import com.olympus.oir.parser.OirParser;
import com.olympus.oir.model.ParsedOirFile;
import java.io.File;
import java.util.Map;

public class TestFragments {
    public static void main(String[] args) throws Exception {
        File f = new File("D:\\phase-01\\assignment1-phase1.oir");
        if (!f.exists()) {
            System.out.println("File not found: " + f.getAbsolutePath());
            return;
        }
        OirParser parser = new OirParser(f);
        ParsedOirFile parsed = parser.parse();
        Map<String, Integer> map = parsed.getFragmentIndexMap();
        System.out.println("Total fragments: " + map.size());
        int count = 0;
        Map<String, Integer> frameMap = parsed.getFrameIndexMap();
        System.out.println("\nTotal frames in frameMap: " + frameMap.size());
        count = 0;
        for(String key : frameMap.keySet()) {
            System.out.println("Frame Key: " + key);
            if (++count > 10) break;
        }
    }
}
