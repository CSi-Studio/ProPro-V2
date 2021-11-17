package net.csibio.propro.algorithm.score.features;

import net.csibio.propro.algorithm.score.ScoreType;
import net.csibio.propro.domain.bean.score.PeakGroup;
import net.csibio.propro.domain.bean.score.ScoreRtPair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Component("rtNormalizerScorer")
public class RtNormalizerScorer {

    @Autowired
    XicScorer xicScorer;
    @Autowired
    LibraryScorer libraryScorer;


//    private float windowLength = 1000;
//    private int binCount = 30;

//    private float rtNormalizationFactor = 1.0f;
//    private int addUpSpectra = 1;
//    private float spacingForSpectraResampling = 0.005f;
//    //scoreForAll use params


    /**
     * return scores.library_corr                     * -0.34664267 +
     * scores.library_norm_manhattan           *  2.98700722 +
     * scores.norm_rt_score                    *  7.05496384 +
     * scores.xcorr_coelution_score            *  0.09445371 +
     * scores.xcorr_shape_score                * -5.71823862 +
     * scores.log_sn_score                     * -0.72989582 +
     * scores.elution_model_fit_score          *  1.88443209;
     *
     * @param peakGroupList   features extracted from chromatogramList in transitionGroup
     * @param normedLibIntMap intensity in transitionList in transitionGroup
     * @return List of overallQuality
     */
    public List<ScoreRtPair> score4Irt(List<PeakGroup> peakGroupList, HashMap<String, Double> normedLibIntMap, double groupRt, int maxIonsCount) {

        List<ScoreRtPair> finalScores = new ArrayList<>();
        List<String> scoreTypes4Irt = ScoreType.usedScoreTypes();

        for (PeakGroup peakGroup : peakGroupList) {
            if (peakGroup.getRightRt() - peakGroup.getLeftRt() < 15d) {
                continue;
            }
            peakGroup.initScore(scoreTypes4Irt.size());
            xicScorer.calcXICScores(peakGroup, normedLibIntMap, scoreTypes4Irt);
//            xicScorer.calculateLogSnScore(peakGroup, scores, defaultScoreTypes);
            libraryScorer.calculateLibraryScores(peakGroup, normedLibIntMap, scoreTypes4Irt);
            peakGroup.put(ScoreType.IonsDelta.getName(), (maxIonsCount - peakGroup.getIonsLow()) * 1d / maxIonsCount, scoreTypes4Irt);
            double deltaWeight = (maxIonsCount - peakGroup.getIonsLow()) * 1d / maxIonsCount;
            peakGroup.put(ScoreType.IonsDelta, deltaWeight, scoreTypes4Irt);

            double ldaScore = calculateLdaPrescore(peakGroup, scoreTypes4Irt);
            ScoreRtPair scoreRtPair = new ScoreRtPair();
            scoreRtPair.setLibRt(groupRt);
            scoreRtPair.setApexRt(peakGroup.getApexRt());
            scoreRtPair.setScore(ldaScore);
            scoreRtPair.setScores(peakGroup);
            finalScores.add(scoreRtPair);
        }

        return finalScores;
    }

    /**
     * The scoreForAll that is really matter to final pairs selection.
     *
     * @param scores pre-calculated
     * @return final scoreForAll
     */
    private double calculateLdaPrescore(PeakGroup scores, List<String> scoreTypes) {
//        return scores.get(ScoreType.LibraryCorr.getName(), scoreTypes) * -0.34664267d +
//                scores.get(ScoreType.LibraryRsmd.getName(), scoreTypes) * 2.98700722d +
//             scores.get(ScoreType.LogSnScore.getName(), scoreTypes) * -0.72989582 +
//        return scores.get(ScoreType.XcorrCoelution.getName(), scoreTypes) * (-0.09445371d) +
//                scores.get(ScoreType.XcorrShape.getName(), scoreTypes) * -5.71823862d;
        return scores.get(ScoreType.XcorrShape.getName(), scoreTypes) +
                scores.get(ScoreType.LibraryDotprod.getName(), scoreTypes) +
                scores.get(ScoreType.LibraryDotprod.getName(), scoreTypes) -
                scores.get(ScoreType.IonsDelta.getName(), scoreTypes);
    }

}
