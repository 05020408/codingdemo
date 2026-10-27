package cn.review.material;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xslf.usermodel.*;

/** Technical probe only: no OCR, notes, chart data or visual reading-order inference. */
public class PptTextExtractor {
    public List<SlideText> extract(InputStream input) throws IOException {
        try (XMLSlideShow presentation=new XMLSlideShow(input)) {
            List<SlideText> result=new ArrayList<>();
            for(int index=0;index<presentation.getSlides().size();index++) {
                XSLFSlide slide=presentation.getSlides().get(index);
                StringBuilder text=new StringBuilder();
                append(slide.getShapes(),text);
                long sourceSlideId=presentation.getCTPresentation().getSldIdLst().getSldIdArray(index).getId();
                result.add(new SlideText(index+1,sourceSlideId,slide.isHidden(),slide.getTitle(),text.toString().strip()));
            }
            return List.copyOf(result);
        }
    }
    private void append(List<? extends XSLFShape> shapes,StringBuilder output) {
        for(XSLFShape shape:shapes) {
            if(shape instanceof XSLFGroupShape group) append(group.getShapes(),output);
            else if(shape instanceof XSLFTable table) {
                for(XSLFTableRow row:table.getRows()) {
                    for(XSLFTableCell cell:row.getCells()) output.append(cell.getText()).append('\t');
                    output.append('\n');
                }
            } else if(shape instanceof XSLFTextShape text) output.append(text.getText()).append('\n');
        }
    }
    public record SlideText(int pageNumber,long sourceSlideId,boolean hidden,String title,String text) {}
}
