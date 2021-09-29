package net.csibio.propro.algorithm.peak;

import lombok.extern.slf4j.Slf4j;
import net.csibio.propro.domain.bean.data.PeptideSpectrum;
import net.csibio.propro.domain.bean.data.RtIntensityPairsDouble;
import net.csibio.propro.domain.bean.score.IonPeak;
import net.csibio.propro.domain.bean.score.PeakGroup;
import net.csibio.propro.domain.bean.score.PeakGroupList;
import net.csibio.propro.domain.db.DataDO;
import net.csibio.propro.domain.options.DeveloperParams;
import net.csibio.propro.domain.options.SigmaSpacing;
import net.csibio.propro.service.DataService;
import net.csibio.propro.service.OverviewService;
import net.csibio.propro.service.PeptideService;
import net.csibio.propro.service.TaskService;
import net.csibio.propro.utils.MathUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;

@Slf4j
@Component("featureExtractor")
public class FeatureExtractor {

    @Autowired
    DataService dataService;
    @Autowired
    OverviewService overviewService;
    @Autowired
    PeptideService peptideService;
    @Autowired
    GaussFilter gaussFilter;
    @Autowired
    PeakPicker peakPicker;
    @Autowired
    SignalToNoiseEstimator signalToNoiseEstimator;
    @Autowired
    ChromatogramPicker chromatogramPicker;
    @Autowired
    FeatureFinder featureFinder;
    @Autowired
    TaskService taskService;

    /**
     * //2021.09.28 lms 在这里将定量方式改为仅计算强度排名前三的三个碎片的强度进行定量
     *
     * @param data         XIC后的数据对象
     * @param intensityMap 得到标准库中peptideRef对应的碎片和强度的键值对
     * @param ss           sigma spacing
     * @return
     */
    public PeakGroupList getExperimentFeature(DataDO data, HashMap<String, Float> intensityMap, SigmaSpacing ss) {
        if (data.getIntMap().isEmpty()) {
            return new PeakGroupList(false);
        }

        HashMap<String, RtIntensityPairsDouble> ionPeaks = new HashMap<>();
        HashMap<String, List<IonPeak>> ionPeakParams = new HashMap<>();

        //对每一个chromatogram进行运算,dataDO中不含有ms1
        HashMap<String, double[]> noise1000Map = new HashMap<>();
        HashMap<String, Double[]> intensitiesMap = new HashMap<>();

        //将没有提取到信号的CutInfo过滤掉,同时将Float类型的参数调整为Double类型进行计算
        for (String cutInfo : intensityMap.keySet()) {
            //获取对应的XIC数据
            float[] intensityArray = data.getIntMap().get(cutInfo);
            //如果没有提取到信号,dataDO为null
            if (intensityArray == null) {
                continue;
            }
            Double[] intensityDoubleArray = new Double[intensityArray.length];
            for (int k = 0; k < intensityArray.length; k++) {
                intensityDoubleArray[k] = (double) intensityArray[k];
            }
            intensitiesMap.put(cutInfo, intensityDoubleArray);
        }

        if (intensitiesMap.size() == 0) {
            return new PeakGroupList(false);
        }
        //计算GaussFilter
        Double[] rtDoubleArray = new Double[data.getRtArray().length];
        for (int k = 0; k < rtDoubleArray.length; k++) {
            rtDoubleArray[k] = Double.parseDouble(data.getRtArray()[k] + "");
        }
        PeptideSpectrum peptideSpectrum = new PeptideSpectrum(rtDoubleArray, intensitiesMap);
        HashMap<String, Double[]> smoothIntensitiesMap = gaussFilter.filter(rtDoubleArray, intensitiesMap, ss);

        //对每一个片段离子选峰
        double libIntSum = MathUtil.sum(intensityMap.values());
        HashMap<String, Double> normedLibIntMap = new HashMap<>();
        for (String cutInfo : intensitiesMap.keySet()) {
            //计算两个信噪比
            double[] noises200 = signalToNoiseEstimator.computeSTN(rtDoubleArray, smoothIntensitiesMap.get(cutInfo), 200, 30);
            double[] noisesOri1000 = signalToNoiseEstimator.computeSTN(rtDoubleArray, intensitiesMap.get(cutInfo), 1000, 30);
            //根据信噪比和峰值形状选择最高峰,用降噪200及平滑过后的图去挑选Peak峰
            RtIntensityPairsDouble maxPeakPairs = peakPicker.pickMaxPeak(rtDoubleArray, smoothIntensitiesMap.get(cutInfo), noises200);
            //根据信噪比和最高峰选择谱图
            if (maxPeakPairs == null) {
                log.info("Error: MaxPeakPairs were null!" + rtDoubleArray.length);
                break;
            }
            List<IonPeak> ionPeakList = chromatogramPicker.pickChromatogram(rtDoubleArray, intensitiesMap.get(cutInfo), smoothIntensitiesMap.get(cutInfo), noisesOri1000, maxPeakPairs);
            ionPeaks.put(cutInfo, maxPeakPairs);
            ionPeakParams.put(cutInfo, ionPeakList);
            noise1000Map.put(cutInfo, noisesOri1000);
            normedLibIntMap.put(cutInfo, intensityMap.get(cutInfo) / libIntSum);
        }
        if (ionPeakParams.size() == 0) {
            return new PeakGroupList(false);
        }

        //挑选理论强度排名前三的三个碎片用于定量
//        List<Map.Entry<String, Float>> entryList = new ArrayList<>(intensityMap.entrySet());
//        List<String> quantifyIons = Lists.reverse(entryList.stream().sorted(Map.Entry.comparingByValue()).toList()).stream().map(Map.Entry::getKey).toList();
//        if (quantifyIons.size() > 3) {
//            quantifyIons = quantifyIons.subList(0, 3);
//        }

        List<PeakGroup> peakGroupFeatureList;
        if (DeveloperParams.USE_NEW_PEAKGROUP_SELECTOR) {
            peakGroupFeatureList = featureFinder.findFeaturesNew(peptideSpectrum, ionPeaks, ionPeakParams, noise1000Map, null);
        } else {
            peakGroupFeatureList = featureFinder.findFeatures(peptideSpectrum, ionPeaks, ionPeakParams, noise1000Map);
        }
        PeakGroupList featureResult = new PeakGroupList(true);
        featureResult.setList(peakGroupFeatureList);
        featureResult.setNormedIntMap(normedLibIntMap);

        return featureResult;
    }
}
