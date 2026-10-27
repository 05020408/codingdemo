package cn.review;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xslf.usermodel.XMLSlideShow;

public final class MaterialFixtures {
  public static byte[] deck(boolean text) throws Exception {
    try (var ppt = new XMLSlideShow();
        var out = new ByteArrayOutputStream()) {
      for (int i = 1; i <= 4; i++) {
        var slide = ppt.createSlide();
        if (i == 2) slide.getXmlObject().setShow(false);
        if (text && i != 3) {
          var box = slide.createTextBox();
          box.setAnchor(new Rectangle2D.Double(40, 40, 600, 180));
          box.setText("PAGE " + i + " ANCHOR\nReview workspace sample");
        }
      }
      ppt.write(out);
      return out.toByteArray();
    }
  }
}
