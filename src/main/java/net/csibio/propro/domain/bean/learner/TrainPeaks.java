package net.csibio.propro.domain.bean.learner;

import lombok.Data;
import net.csibio.propro.domain.bean.score.SimpleFeatureScores;

import java.util.List;

/**
 * Created by Nico Wang Ruimin
 * Time: 2018-06-13 16:00
 */
@Data
public class TrainPeaks {

    List<SimpleFeatureScores> bestTargets;

    List<SimpleFeatureScores> topDecoys;
}
