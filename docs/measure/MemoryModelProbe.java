import dev.localintelligence.core.model.gguf.*;
import dev.localintelligence.core.hub.*;
import java.nio.file.*;

public class Probe {
    public static void main(String[] args) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(args[0]));
        System.out.println("file             " + args[0]);
        System.out.println("fileBytes        " + bytes.length);
        GgufHeader h = GgufParser.INSTANCE.parse(bytes, new GgufLimits(), GgufParser.TruncationPolicy.STRICT);
        System.out.println("ggufVersion      " + h.getVersion());
        System.out.println("headerBytes      " + h.getHeaderBytes());
        System.out.println("tensors          " + h.getTensors().size());
        System.out.println("complete         " + h.isComplete());
        System.out.println("declaredWeights  " + h.getDeclaredWeightBytes());
        System.out.println("tableParams      " + h.getTensorTableParameterCount());
        System.out.println("dominantQuant    " + h.getDominantQuantType());
        System.out.println("warnings         " + h.getWarnings().size());
        ModelMemoryEstimator est = new ModelMemoryEstimator();
        for (long ctx : new long[]{2048L, 4096L}) {
            MemoryEstimate e = est.estimate(h, ctx, KvCacheType.F16, null);
            System.out.println("--- context " + ctx + " ---");
            System.out.println("  basis     " + e.getBasis());
            System.out.println("  weights   " + e.getWeightsBytes());
            System.out.println("  kv        " + e.getKvCacheBytes()
                + "  (layers=" + e.getLayers() + " kvHeads=" + e.getKvHeads()
                + " headDim=" + e.getHeadDimension() + " bpw=" + e.getBitsPerWeight() + ")");
            System.out.println("  overhead  " + e.getOverheadBytes());
            System.out.println("  TOTAL     " + e.getTotalBytes());
        }
        GgufQuant q = GgufQuant.Companion.fromFileName("tinyllama-1.1b.Q4_K_M.gguf");
        System.out.println("--- pre-download, name+size only, ctx 4096 ---");
        System.out.println("  quant          " + q + "  bpw=" + q.getBitsPerWeight() + " n=" + q.getMeasuredSamples());
        Long declared = HuggingFaceClient.Companion.parseParameterCount("tinyllama-1.1b.Q4_K_M.gguf");
        System.out.println("  declaredParams " + declared);
        MemoryRange r = PreDownloadMemoryModel.INSTANCE.estimateRange(bytes.length, q, 4096, declared);
        System.out.println("  low            " + r.getLowBytes());
        System.out.println("  central        " + r.getCentralBytes());
        System.out.println("  high           " + r.getHighBytes());
        MemoryRange rn = PreDownloadMemoryModel.INSTANCE.estimateRange(bytes.length, null, 4096, declared);
        System.out.println("  no-quant central " + rn.getCentralBytes() + "  high " + rn.getHighBytes());
        MemoryRange rp = PreDownloadMemoryModel.INSTANCE.estimateRange(bytes.length, q, 4096, null);
        System.out.println("  no-declared central " + rp.getCentralBytes() + "  high " + rp.getHighBytes());
    }
}
