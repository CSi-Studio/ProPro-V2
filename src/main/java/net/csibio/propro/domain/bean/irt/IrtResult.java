package net.csibio.propro.domain.bean.irt;

import lombok.Data;
import net.csibio.propro.domain.bean.common.ListPairs;
import net.csibio.propro.domain.bean.score.SlopeIntercept;

import java.util.List;

@Data
public class IrtResult {

    String libraryId;

    SlopeIntercept si;

    /**
     * 选中的Peptide序列,长度与selected中数组长度一致
     * selected peptides
     */
    List<String> sp;

    /**
     * 未选中的Peptide序列, 长度与unselected中数组长度一致
     * <p>
     * unselected peptides
     */
    List<String> usp;

    /**
     * 存储所有选中的点,横坐标为理论RT(libRt),纵坐标为实际RT(realRt)
     */
    ListPairs selected;

    /**
     * 存储所有未选中的点,横坐标为理论RT(libRt),纵坐标为实际RT(realRt)
     */
    ListPairs unselected;
}
