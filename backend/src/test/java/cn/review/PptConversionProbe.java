package cn.review;

import cn.review.material.PptTextExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;

/** Local, manual technical validation; intentionally not an upload API. */
public class PptConversionProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=3) throw new IllegalArgumentException("Expected input PPTX, ignored output directory, soffice executable");
        Path source=Path.of(args[0]).toAbsolutePath(),output=Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        if(source.getFileName().toString().equals("mapping-fixture.pptx") && !Files.exists(source)) createFixture(source);
        List<PptTextExtractor.SlideText> slides;
        try(var input=Files.newInputStream(source)){slides=new PptTextExtractor().extract(input);}
        long start=System.nanoTime();
        String filter="pdf:impress_pdf_Export:{\"ExportHiddenSlides\":{\"type\":\"boolean\",\"value\":\"true\"},\"ExportNotesPages\":{\"type\":\"boolean\",\"value\":\"false\"}}";
        // The Windows LibreOffice launcher consumes one quote-escaping layer.
        if(System.getProperty("os.name").startsWith("Windows")) filter=filter.replace("\"","\\\"");
        var process=new ProcessBuilder(args[2],"-env:UserInstallation="+output.resolve("profile").toUri(),"--headless","--convert-to",filter,"--outdir",output.toString(),source.toString()).redirectErrorStream(true).redirectOutput(output.resolve("converter.log").toFile()).start();
        if(!process.waitFor(120,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();throw new IllegalStateException("Conversion timed out");}
        if(process.exitValue()!=0) throw new IllegalStateException("Converter failed; inspect local log");
        Path pdf=output.resolve(source.getFileName().toString().replaceFirst("(?i)\\.pptx$",".pdf"));
        var missingAnchors=new ArrayList<Integer>();
        try(var document=Loader.loadPDF(pdf.toFile())) {
            if(document.getNumberOfPages()!=slides.size()) throw new IllegalStateException("Page count mismatch; mapping must not be published");
            var stripper=new PDFTextStripper();
            for(int i=0;i<slides.size();i++) {
                stripper.setStartPage(i+1);stripper.setEndPage(i+1);
                String pdfText=stripper.getText(document).replaceAll("\\s+","");
                String original=slides.get(i).text().lines().filter(s->!s.isBlank()).findFirst().orElse("").replaceAll("\\s+","");
                String anchor=original.substring(0,Math.min(original.length(),12));
                if(!anchor.isEmpty() && !pdfText.contains(anchor)) missingAnchors.add(i+1);
            }
            var renderer=new PDFRenderer(document);
            var samples=new TreeSet<>(List.of(0,slides.size()/2,slides.size()-1));
            for(int index:samples)ImageIO.write(renderer.renderImageWithDPI(index,90),"PNG",output.resolve("page-"+(index+1)+".png").toFile());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("result.json").toFile(),Map.of("originalPages",slides.size(),"pdfPages",document.getNumberOfPages(),"hiddenPages",slides.stream().filter(PptTextExtractor.SlideText::hidden).count(),"unmatchedFirstLineAnchors",missingAnchors,"sampledPages",samples.stream().map(i->i+1).toList(),"elapsedMs",(System.nanoTime()-start)/1_000_000));
        }
        System.out.println(Files.readString(output.resolve("result.json")));
    }
    private static void createFixture(Path file) throws Exception {
        try(var deck=new XMLSlideShow()) {
            deck.createSlide().createTextBox().setText("PAGE-01-NORMAL");
            var hidden=deck.createSlide();hidden.setHidden(true);hidden.createTextBox().setText("PAGE-02-HIDDEN");
            deck.createSlide();
            deck.createSlide().createTextBox().setText("PAGE-04-REPEATED\nPAGE-04-REPEATED");
            var image=new BufferedImage(320,100,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();graphics.setColor(Color.WHITE);graphics.fillRect(0,0,320,100);graphics.setColor(Color.BLACK);graphics.drawString("PAGE-05-IMAGE-ONLY",25,50);graphics.dispose();
            var bytes=new java.io.ByteArrayOutputStream();ImageIO.write(image,"PNG",bytes);
            deck.createSlide().createPicture(deck.addPicture(bytes.toByteArray(),PictureData.PictureType.PNG)).setAnchor(new java.awt.geom.Rectangle2D.Double(20,20,320,100));
            try(var output=Files.newOutputStream(file)){deck.write(output);}
        }
    }
}
