package cn.review;

import cn.review.material.PptTextExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Manual command-line probe, writes teacher material only to an ignored local directory. */
public class PptProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Expected input PPTX and output JSON paths");
        Path input=Path.of(args[0]),output=Path.of(args[1]);
        var bytes=Files.readAllBytes(input);
        long start=System.nanoTime();
        var slides=new PptTextExtractor().extract(new java.io.ByteArrayInputStream(bytes));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),Map.of("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),"slides",slides,"elapsedMs",(System.nanoTime()-start)/1_000_000));
        System.out.println("Pages="+slides.size()+", hidden="+slides.stream().filter(PptTextExtractor.SlideText::hidden).count()+", nonempty="+slides.stream().filter(s->!s.text().isBlank()).count());
    }
}
