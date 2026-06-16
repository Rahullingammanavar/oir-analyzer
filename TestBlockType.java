import com.olympus.oir.parser.OirParser;
import com.olympus.oir.model.ParsedOirFile;
import com.olympus.oir.model.OirBlock;
import java.io.File;
import java.util.Map;
import java.util.List;

public class TestBlockType {
    public static void main(String[] args) throws Exception {
        File f = new File("D:\\phase-01\\assignment1-phase1.oir");
        OirParser parser = new OirParser(f);
        ParsedOirFile parsed = parser.parse();
        
        Map<String, Integer> map = parsed.getFragmentIndexMap();
        List<OirBlock> blocks = parsed.getBlocks();
        
        String firstKey = map.keySet().iterator().next();
        int blockNo = map.get(firstKey);
        
        OirBlock target = blocks.get(blockNo);
        OirBlock next = blocks.get(blockNo + 1);
        
        System.out.println("First map key: " + firstKey + " -> blockNo " + blockNo);
        System.out.println("Block at blockNo: " + target.getAttribute());
        System.out.println("Block at blockNo+1: " + next.getAttribute());
    }
}
