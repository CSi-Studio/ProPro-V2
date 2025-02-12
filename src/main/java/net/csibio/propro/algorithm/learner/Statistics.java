package net.csibio.propro.algorithm.learner;

import net.csibio.propro.constants.constant.Constants;
import net.csibio.propro.domain.Result;
import net.csibio.propro.domain.bean.learner.ErrorStat;
import net.csibio.propro.domain.bean.learner.LearningParams;
import net.csibio.propro.domain.bean.learner.Pi0Est;
import net.csibio.propro.domain.bean.learner.StatMetrics;
import net.csibio.propro.domain.bean.score.SelectedPeakGroup;
import net.csibio.propro.utils.ArrayUtil;
import net.csibio.propro.utils.MathUtil;
import net.csibio.propro.utils.ProProUtil;
import net.csibio.propro.utils.SortUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by Nico Wang Ruimin
 * Time: 2018-06-13 16:55
 */
@Component("statistics")
public class Statistics {

    public static final Logger logger = LoggerFactory.getLogger(Statistics.class);

    public void pNormalizer(List<SelectedPeakGroup> targetScores, List<SelectedPeakGroup> decoyScores) {
        Double[] decoyScoresArray = ProProUtil.buildMainScoreArray(decoyScores, false);
        double mean = MathUtil.mean(decoyScoresArray);
        double std = MathUtil.std(decoyScoresArray, mean);
        double args;
        for (SelectedPeakGroup sfs : targetScores) {
            args = (sfs.getTotalScore() - mean) / std;
            sfs.setPValue(1 - (0.5 * (1.0 + MathUtil.erf(args / Math.sqrt(2.0)))));
        }
    }

    public void pEmpirical(List<SelectedPeakGroup> targetScores, List<SelectedPeakGroup> decoyScores) {
        List<SelectedPeakGroup> totalScores = new ArrayList<>();
        totalScores.addAll(targetScores);
        totalScores.addAll(decoyScores);

        totalScores = SortUtil.sortByMainScore(totalScores, true);
        int decoyCount = 0;
        int decoyTotal = decoyScores.size();
        double fix = 1.0 / decoyTotal;
        for (SelectedPeakGroup sfs : totalScores) {
            if (sfs.getDecoy()) {
                decoyCount++;
            } else {
                double pValue = (double) decoyCount / decoyTotal;
                if (pValue < fix) {
                    sfs.setPValue(fix);
                } else {
                    sfs.setPValue(pValue);
                }
            }
        }
    }

    /**
     * Calculate qvalues.
     * targets的qvalue需要从大到小排序
     */
    public void qvalue(List<SelectedPeakGroup> targets, double pi0, boolean pfdr) {
        Double[] pValues = ProProUtil.buildPValueArray(targets, false);
        int pValueLength = targets.size();
        double[] v = ArrayUtil.rank(pValues);
        for (int i = 0; i < pValueLength; i++) {
            if (pfdr) {
                targets.get(i).setQValue((pi0 * pValueLength * targets.get(i).getPValue()) / (v[i] * (1 - Math.pow((1 - targets.get(i).getPValue()), pValueLength))));
            } else {
                targets.get(i).setQValue((pi0 * pValueLength * targets.get(i).getPValue()) / v[i]);
            }
        }
        targets.get(0).setQValue(Math.min(targets.get(0).getQValue(), 1));

        for (int i = 1; i < pValueLength - 1; i++) {
            targets.get(i).setQValue(Math.min(targets.get(i).getQValue(), targets.get(i - 1).getQValue()));
        }
    }

    /**
     * Estimate final results.
     * TODO 没有实现 pep(lfdr);
     */
    public ErrorStat errorStatistics(List<SelectedPeakGroup> scores, LearningParams learningParams) {

        List<SelectedPeakGroup> targets = new ArrayList<>();
        List<SelectedPeakGroup> decoys = new ArrayList<>();
        for (SelectedPeakGroup featureScores : scores) {
            if (featureScores.getDecoy()) {
                decoys.add(featureScores);
            } else {
                targets.add(featureScores);
            }
        }

        return errorStatistics(targets, decoys, learningParams);
    }

    /**
     * Estimate final results.
     * TODO 没有实现 pep(lfdr);
     */
    public ErrorStat errorStatistics(List<SelectedPeakGroup> targets, List<SelectedPeakGroup> decoys, LearningParams learningParams) {

        ErrorStat errorStat = new ErrorStat();
        List<SelectedPeakGroup> sortedTargets = SortUtil.sortByMainScore(targets, false);
        List<SelectedPeakGroup> sortedDecoys = SortUtil.sortByMainScore(decoys, false);

        //compute p-values using decoy scores;
        if (learningParams.isParametric()) {
            pNormalizer(sortedTargets, sortedDecoys);
        } else {
            pEmpirical(sortedTargets, sortedDecoys);
        }
        Pi0Est pi0Est = new Pi0Est();
        if (sortedTargets.get(0).getTotalScore() > sortedDecoys.get(sortedDecoys.size() - 1).getTotalScore()) {
            pi0Est.setPi0(1d / Constants.PRECISION);
        } else {
            //estimate pi0;
            pi0Est = pi0Est(sortedTargets, learningParams.getPi0Lambda(), learningParams.getPi0Method(), learningParams.isPi0SmoothLogPi0());
            if (pi0Est == null) {
                return null;
            }
        }

        //compute q-value;
        qvalue(sortedTargets, pi0Est.getPi0(), learningParams.isPFdr());
        //compute other metrics;
        StatMetrics statMetrics = statMetrics(sortedTargets, pi0Est.getPi0(), learningParams.isPFdr());

        errorStat.setBestFeatureScoresList(targets);
        errorStat.setStatMetrics(statMetrics);
        errorStat.setPi0Est(pi0Est);

        return errorStat;
    }

    /**
     * Finds cut-off target scoreForAll for specified false discovery rate(fdr).
     */
    public Double findCutoff(List<SelectedPeakGroup> topTargets, List<SelectedPeakGroup> topDecoys, LearningParams learningParams, Double cutoff) {
        ErrorStat errorStat = errorStatistics(topTargets, topDecoys, learningParams);

        List<SelectedPeakGroup> bestScores = errorStat.getBestFeatureScoresList();
        double[] qValueCutoffAbs = new double[bestScores.size()];
        for (int i = 0; i < bestScores.size(); i++) {
            qValueCutoffAbs[i] = Math.abs(bestScores.get(i).getQValue() - cutoff);
        }
        int k = MathUtil.argmin(qValueCutoffAbs);
        return bestScores.get(k).getTotalScore();
    }

    /**
     * Calculate P relative scores.
     */
    private Pi0Est pi0Est(List<SelectedPeakGroup> targets, Double[] lambda, String pi0Method, boolean smoothLogPi0) {

        Pi0Est pi0EstResults = new Pi0Est();
        int numOfPvalue = targets.size();
        int numOfLambda = 1;
        if (lambda != null) {
            numOfLambda = lambda.length;
        }
        Double[] meanPL = new Double[numOfPvalue];
        Double[] pi0Lambda = new Double[numOfLambda];
        Double pi0;
        Double[] pi0Smooth = new Double[numOfLambda];
        Double[] pi0s = new Double[numOfLambda];
        if (numOfLambda < 4) {
            logger.error("Pi0Est lambda Error, numOfLambda < 4");
            return null;
        }
        for (int i = 0; i < numOfLambda; i++) {
            for (int j = 0; j < numOfPvalue; j++) {
                if (targets.get(j).getPValue() < lambda[i]) {
                    meanPL[j] = 0d;
                } else {
                    meanPL[j] = 1d;
                }
            }
            pi0Lambda[i] = MathUtil.mean(meanPL) / (1 - lambda[i]);
        }
        if (pi0Method.equals("smoother")) {
            if (smoothLogPi0) {
                for (int i = 0; i < numOfLambda; i++) {
                    pi0s[i] = Math.log(pi0Lambda[i]);
                }
                Result<Double[]> pi0SmoothResult = MathUtil.lagrangeInterpolation(lambda, pi0s);
                if (pi0SmoothResult.isSuccess()) {
                    pi0Smooth = pi0SmoothResult.getData();
                }
                for (int i = 0; i < numOfLambda; i++) {
                    pi0Smooth[i] = Math.exp(pi0Smooth[i]);
                }
            } else {
                Result<Double[]> pi0SmoothResult = MathUtil.lagrangeInterpolation(lambda, pi0s);
                if (pi0SmoothResult.isSuccess()) {
                    pi0Smooth = pi0SmoothResult.getData();
                }
            }
            pi0 = Math.min(pi0Smooth[numOfLambda - 1], (double) 1);
        } else if (pi0Method.equals("bootstrap")) {
            Double[] sortedPi0Lambda = pi0Lambda.clone();
            Arrays.sort(sortedPi0Lambda);
            int w;
            double[] mse = new double[numOfLambda];
            for (int i = 0; i < numOfLambda; i++) {
                w = ProProUtil.countOverThreshold(targets, lambda[i]);
                mse[i] = (w / (Math.pow(numOfPvalue, 2) * Math.pow((1 - lambda[i]), 2))) * (1 - (double) w / numOfPvalue) + Math.pow((pi0Lambda[i] - sortedPi0Lambda[0]), 2);
            }
            double min = Double.MAX_VALUE;
            int index = 0;
            for (int i = 0; i < mse.length; i++) {
                if (pi0Lambda[i] > 0 && mse[i] < min) {
                    min = mse[i];
                    index = i;
                }
            }
            pi0 = Math.min(pi0Lambda[index], 1);
            pi0Smooth = null;
        } else {
            logger.error("Pi0Est Method Error.No Method Called " + pi0Method);
            return null;
        }
        if (pi0 <= 0) {
            logger.error("Pi0Est Pi0 Error -- pi0<=0");
            return null;
        }
        pi0EstResults.setPi0(pi0);
        pi0EstResults.setPi0Smooth(pi0Smooth);
        pi0EstResults.setLambda(lambda);
        pi0EstResults.setPi0Lambda(pi0Lambda);
        return pi0EstResults;
    }

    private StatMetrics statMetrics(List<SelectedPeakGroup> scores, Double pi0, boolean pfdr) {
        StatMetrics results = new StatMetrics();
        int numOfPvalue = scores.size();
        int[] numPositives = ProProUtil.countPValueNumPositives(scores);
        int[] numNegatives = new int[numOfPvalue];
        for (int i = 0; i < numOfPvalue; i++) {
            numNegatives[i] = numOfPvalue - numPositives[i];
        }
        double numNull = pi0 * numOfPvalue;
        double[] tp = new double[numOfPvalue];
        double[] fp = new double[numOfPvalue];
        double[] tn = new double[numOfPvalue];
        double[] fn = new double[numOfPvalue];
        double[] fpr = new double[numOfPvalue];
        double[] fdr = new double[numOfPvalue];
        double[] fnr = new double[numOfPvalue];
        double[] sens = new double[numOfPvalue];
        double[] svalues;
        for (int i = 0; i < numOfPvalue; i++) {
            tp[i] = (double) numPositives[i] - numNull * scores.get(i).getPValue();
            fp[i] = numNull * scores.get(i).getPValue();
            tn[i] = numNull * (1.0 - scores.get(i).getPValue());
            fn[i] = (double) numNegatives[i] - numNull * (1.0 - scores.get(i).getPValue());
            fpr[i] = fp[i] / numNull;
            if (numPositives[i] == 0) {
                fdr[i] = 0.0;
            } else {
                fdr[i] = fp[i] / (double) numPositives[i];
            }
            if (numNegatives[i] == 0) {
                fnr[i] = 0.0;
            } else {
                fnr[i] = fn[i] / (double) numNegatives[i];
            }
            if (pfdr) {
                fdr[i] /= (1.0 - (1.0 - Math.pow(scores.get(i).getPValue(), numOfPvalue)));
                fnr[i] /= 1.0 - Math.pow(scores.get(i).getPValue(), numOfPvalue);
                if (scores.get(i).getPValue() == 0) {
                    fdr[i] = 1.0 / numOfPvalue;
                    fnr[i] = 1.0 / numOfPvalue;
                }
            }
            sens[i] = tp[i] / ((double) numOfPvalue - numNull);
            if (sens[i] < 0.0) {
                sens[i] = 0.0;
            }
            if (sens[i] > 1.0) {
                sens[i] = 1.0;
            }
            if (fdr[i] < 0.0) {
                fdr[i] = 0.0;
            }
            if (fdr[i] > 1.0) {
                fdr[i] = 1.0;
            }
            if (fnr[i] < 0.0) {
                fnr[i] = 0.0;
            }
            if (fnr[i] > 1.0) {
                fnr[i] = 1.0;
            }
            scores.get(i).setFdr(fdr[i]);
        }

        svalues = ArrayUtil.reverse(MathUtil.cumMax(ArrayUtil.reverse(sens)));
        results.setTp(tp);
        results.setFp(fp);
        results.setTn(tn);
        results.setFn(fn);
        results.setFpr(fpr);
        results.setFdr(fdr);
        results.setFnr(fnr);
        results.setSvalue(svalues);
        return results;
    }
}
