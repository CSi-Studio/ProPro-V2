package net.csibio.propro.algorithm.learner.classifier;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import lombok.extern.slf4j.Slf4j;
import net.csibio.propro.algorithm.score.ScoreType;
import net.csibio.propro.domain.bean.data.PeptideScores;
import net.csibio.propro.domain.bean.learner.*;
import net.csibio.propro.domain.bean.score.FinalPeakGroupScore;
import net.csibio.propro.domain.bean.score.PeakGroupScores;
import net.csibio.propro.utils.ProProUtil;
import org.apache.commons.math3.linear.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Slf4j
@Component("lda")
public class Lda extends Classifier {

    /**
     * @param scores
     * @param learningParams
     * @return
     */
    public HashMap<String, Double> classifier(List<PeptideScores> scores, LearningParams learningParams, List<String> scoreTypes) {
        log.info("开始训练学习数据权重");
        if (scores.size() < 500) {
            learningParams.setXevalNumIter(10);
            learningParams.setSsIterationFdr(0.02);
            learningParams.setProgressiveRate(0.8);
        }
        int neval = learningParams.getTrainTimes();
        List<HashMap<String, Double>> weightsMapList = new ArrayList<>();
        for (int i = 0; i < neval; i++) {
            log.info("开始第" + i + "轮尝试,总计" + neval + "轮");
            LDALearnData ldaLearnData = learnRandomized(scores, learningParams);
            if (ldaLearnData == null) {
                log.info("跳过本轮训练");
                continue;
            }
            score(scores, ldaLearnData.getWeightsMap(), scoreTypes);
            List<FinalPeakGroupScore> featureScoresList = ProProUtil.findTopFeatureScores(scores, ScoreType.WeightedTotalScore.getName(), scoreTypes, false);
            int count = 0;
            ErrorStat errorStat = statistics.errorStatistics(featureScoresList, learningParams);
            count = ProProUtil.checkFdr(errorStat.getStatMetrics().getFdr(), learningParams.getFdr());
            if (count > 0) {
                log.info("本轮尝试有效果:检测结果:" + count + "个");
            }
            weightsMapList.add(ldaLearnData.getWeightsMap());
            if (learningParams.isDebug()) {
                break;
            }
        }

        return ProProUtil.averagedWeights(weightsMapList);
    }

    public LDALearnData learnRandomized(List<PeptideScores> scores, LearningParams learningParams) {
        LDALearnData ldaLearnData = new LDALearnData();
        try {
            TrainData trainData = ProProUtil.split(scores, learningParams.getTrainTestRatio(), learningParams.isDebug(), learningParams.getScoreTypes());

            TrainPeaks trainPeaks = selectFirstTrainPeaks(trainData, learningParams);

            HashMap<String, Double> weightsMap = learn(trainPeaks, learningParams.getMainScore(), learningParams.getScoreTypes());
            log.info("Train Weight:" + JSONArray.toJSONString(weightsMap));

            //根据weightsMap计算子分数的加权总分
            score(trainData, weightsMap, learningParams.getScoreTypes());
            weightsMap = new HashMap<>();
            HashMap<String, Double> lastWeightsMap = new HashMap<>();
            for (int times = 0; times < learningParams.getXevalNumIter(); times++) {
                TrainPeaks trainPeaksTemp = selectTrainPeaks(trainData, ScoreType.WeightedTotalScore.getName(), learningParams, learningParams.getSsIterationFdr());
                lastWeightsMap = weightsMap;
                weightsMap = learn(trainPeaksTemp, ScoreType.WeightedTotalScore.getName(), learningParams.getScoreTypes());
                log.info("Train Weight:" + JSONArray.toJSONString(weightsMap));
                for (Double value : weightsMap.values()) {
                    if (value == null || Double.isNaN(value)) {
                        log.info("本轮训练一坨屎:" + JSON.toJSONString(weightsMap));
                        continue;
                    }
                }
                if (lastWeightsMap.size() != 0) {
                    for (String key : weightsMap.keySet()) {
                        weightsMap.put(key, weightsMap.get(key) * learningParams.getProgressiveRate() + lastWeightsMap.get(key) * (1d - learningParams.getProgressiveRate()));
                    }
                }
                score(trainData, weightsMap, learningParams.getScoreTypes());
            }
            ldaLearnData.setWeightsMap(weightsMap);
            return ldaLearnData;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private TrainPeaks selectFirstTrainPeaks(TrainData trainData, LearningParams learningParams) {
        List<FinalPeakGroupScore> decoyPeaks = new ArrayList<>();
        List<String> scoreTypes = learningParams.getScoreTypes();
        for (PeptideScores peptideScores : trainData.getDecoys()) {
            PeakGroupScores topDecoy = null;
            double maxMainScore = -Double.MAX_VALUE;
            for (PeakGroupScores peakGroupScores : peptideScores.getScoreList()) {
                double mainScore = peakGroupScores.get(ScoreType.InitScore.getName(), scoreTypes);
                if (mainScore > maxMainScore) {
                    maxMainScore = mainScore;
                    topDecoy = peakGroupScores;
                }
            }
            FinalPeakGroupScore finalPeakGroupScore = new FinalPeakGroupScore();
            finalPeakGroupScore.setScores(topDecoy.getScores());
            decoyPeaks.add(finalPeakGroupScore);
        }
        TrainPeaks trainPeaks = new TrainPeaks();
        trainPeaks.setTopDecoys(decoyPeaks);

        FinalPeakGroupScore bestTargetScore = new FinalPeakGroupScore(learningParams.getScoreTypes().size());
        bestTargetScore.put(ScoreType.XcorrShape.getName(), 1d, scoreTypes);
        bestTargetScore.put(ScoreType.XcorrShapeWeighted.getName(), 1d, scoreTypes);
        bestTargetScore.put(ScoreType.XcorrCoelution.getName(), 0d, scoreTypes);
        bestTargetScore.put(ScoreType.XcorrCoelutionWeighted.getName(), 0d, scoreTypes);
        bestTargetScore.put(ScoreType.LibraryCorr.getName(), 1d, scoreTypes);
        bestTargetScore.put(ScoreType.LibraryRsmd.getName(), 0d, scoreTypes);
        bestTargetScore.put(ScoreType.LibraryManhattan.getName(), 0d, scoreTypes);
        bestTargetScore.put(ScoreType.LibraryDotprod.getName(), 1d, scoreTypes);
        bestTargetScore.put(ScoreType.LibrarySangle.getName(), 0d, scoreTypes);
        bestTargetScore.put(ScoreType.LibraryRootmeansquare.getName(), 0d, scoreTypes);
        bestTargetScore.put(ScoreType.LogSnScore.getName(), 5d, scoreTypes);
        bestTargetScore.put(ScoreType.NormRtScore.getName(), 0d, scoreTypes);
        bestTargetScore.put(ScoreType.IntensityScore.getName(), 1d, scoreTypes);
        bestTargetScore.put(ScoreType.IsotopeCorrelationScore.getName(), 1d, scoreTypes);
        bestTargetScore.put(ScoreType.IsotopeOverlapScore.getName(), 0d, scoreTypes);
        bestTargetScore.put(ScoreType.MassdevScore.getName(), 0d, scoreTypes);
        bestTargetScore.put(ScoreType.MassdevScoreWeighted.getName(), 0d, scoreTypes);
        bestTargetScore.put(ScoreType.BseriesScore.getName(), 4d, scoreTypes);
        bestTargetScore.put(ScoreType.YseriesScore.getName(), 10d, scoreTypes);


        List<FinalPeakGroupScore> bestTargets = new ArrayList<>();
        bestTargets.add(bestTargetScore);
        trainPeaks.setBestTargets(bestTargets);
        return trainPeaks;
    }

    /**
     * 使用apache的svd库进行计算
     *
     * @param trainPeaks
     * @param skipScoreType 需要在结果中剔除的主分数,如果为空则不删除
     * @return key为子分数的名称, value是该子分数的权重值
     */
    public HashMap<String, Double> learn(TrainPeaks trainPeaks, String skipScoreType, List<String> scoreTypes) {

        int totalLength = trainPeaks.getBestTargets().size() + trainPeaks.getTopDecoys().size();
        int scoreTypesCount = 0;
        if (scoreTypes.contains(skipScoreType)) {
            scoreTypesCount = scoreTypes.size() - 1;
        } else {
            scoreTypesCount = scoreTypes.size();
        }

        //先将需要进入学习的打分转化为二维矩阵
        RealMatrix scoresMatrix = new Array2DRowRealMatrix(totalLength, scoreTypesCount);
        RealVector labelVector = new ArrayRealVector(totalLength);
        int k = 0;
        for (FinalPeakGroupScore sfs : trainPeaks.getBestTargets()) {
            int i = 0;
            for (String scoreType : scoreTypes) {
                if (scoreType.equals(skipScoreType)) {
                    continue;
                }
                scoresMatrix.setEntry(k, i, sfs.get(scoreType, scoreTypes));
                i++;
            }
            labelVector.setEntry(k, 1);
            k++;
        }
        for (FinalPeakGroupScore sfs : trainPeaks.getTopDecoys()) {
            int i = 0;
            for (String scoreType : scoreTypes) {
                if (scoreType.equals(skipScoreType)) {
                    continue;
                }
                scoresMatrix.setEntry(k, i, sfs.get(scoreType, scoreTypes));
                i++;
            }
            labelVector.setEntry(k, 0);
            k++;
        }

        //计算SVD的解
        SingularValueDecomposition solver = new SingularValueDecomposition(scoresMatrix);
        RealVector realVector = solver.getSolver().solve(labelVector);

        //输出最终的权重值
        HashMap<String, Double> weightsMap = new HashMap<>();
        int tempJ = 0;
        for (String key : scoreTypes) {
            if (key.equals(skipScoreType)) {
                continue;
            }
            weightsMap.put(key, realVector.getEntry(tempJ));
            tempJ++;
        }

        for (Double value : weightsMap.values()) {
            if (value == null || Double.isNaN(value)) {
                log.info("本轮训练结果很差:" + JSON.toJSONString(weightsMap));
                return null;
            }
        }
        return weightsMap;
    }
}
