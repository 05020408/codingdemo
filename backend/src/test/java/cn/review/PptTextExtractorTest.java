package cn.review;

import cn.review.material.PptTextExtractor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PptTextExtractorTest {
    @Test void preservesOriginalSequenceHiddenAndEmptySlidesAndNestedText() throws Exception {
        byte[] bytes;
        try(var deck=new XMLSlideShow();var output=new ByteArrayOutputStream()) {
            deck.createSlide().createTextBox().setText("第一页：软件过程");
            var hidden=deck.createSlide();hidden.setHidden(true);hidden.createTextBox().setText("第二页：隐藏内容");
            deck.createSlide();
            var grouped=deck.createSlide();grouped.createGroup().createTextBox().setText("第四页：组合形状");
            var row=grouped.createTable().addRow();row.addCell().setText("表格知识点");
            deck.write(output);bytes=output.toByteArray();
        }
        var extractor=new PptTextExtractor();
        var slides=extractor.extract(new ByteArrayInputStream(bytes));
        assertThat(slides).hasSize(4);
        assertThat(slides).extracting(PptTextExtractor.SlideText::pageNumber).containsExactly(1,2,3,4);
        assertThat(slides.get(1).hidden()).isTrue();
        assertThat(slides.get(2).text()).isEmpty();
        assertThat(slides.get(3).text()).contains("组合形状","表格知识点");
        assertThat(extractor.extract(new ByteArrayInputStream(bytes))).extracting(PptTextExtractor.SlideText::sourceSlideId).containsExactlyElementsOf(slides.stream().map(PptTextExtractor.SlideText::sourceSlideId).toList());
    }
    @Test void damagedContentCannotBeReportedAsSuccessful() {
        assertThatThrownBy(()->new PptTextExtractor().extract(new ByteArrayInputStream(new byte[]{1,2,3}))).isInstanceOf(Exception.class);
    }
}
