package net.csibio.propro.algorithm.extract;

import lombok.extern.slf4j.Slf4j;
import net.csibio.aird.bean.Compressor;
import net.csibio.aird.bean.MzIntensityPairs;
import net.csibio.aird.bean.WindowRange;
import net.csibio.aird.bean.common.Eic;
import net.csibio.aird.parser.DIAParser;
import net.csibio.propro.algorithm.core.CoreFunc;
import net.csibio.propro.algorithm.learner.classifier.Lda;
import net.csibio.propro.algorithm.score.features.DIAScorer;
import net.csibio.propro.algorithm.score.scorer.Scorer;
import net.csibio.propro.algorithm.stat.StatConst;
import net.csibio.propro.constants.constant.Constants;
import net.csibio.propro.constants.enums.ResultCode;
import net.csibio.propro.constants.enums.TaskStatus;
import net.csibio.propro.domain.Result;
import net.csibio.propro.domain.bean.common.AnyPair;
import net.csibio.propro.domain.bean.common.IntegerPair;
import net.csibio.propro.domain.bean.peptide.FragmentInfo;
import net.csibio.propro.domain.bean.peptide.PeptideCoord;
import net.csibio.propro.domain.db.*;
import net.csibio.propro.domain.options.AnalyzeParams;
import net.csibio.propro.domain.query.BlockIndexQuery;
import net.csibio.propro.domain.vo.RunDataVO;
import net.csibio.propro.exceptions.XException;
import net.csibio.propro.service.*;
import net.csibio.propro.utils.ArrayUtil;
import net.csibio.propro.utils.ConvolutionUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component("extractor")
public class Extractor {

    @Autowired
    LibraryService libraryService;
    @Autowired
    OverviewService overviewService;
    @Autowired
    BlockIndexService blockIndexService;
    @Autowired
    RunService runService;
    @Autowired
    DataService dataService;
    @Autowired
    PeptideService peptideService;
    @Autowired
    Scorer scorer;
    @Autowired
    TaskService taskService;
    @Autowired
    CoreFunc coreFunc;
    @Autowired
    Lda lda;
    @Autowired
    DIAScorer diaScorer;

    /**
     * 提取XIC的核心函数,最终返回提取到XIC的Peptide数目
     * 目前只支持MS2的XIC提取
     *
     * @param params 将XIC提取,选峰及打分合并在一个步骤中执行,可以完整的省去一次IO读取及解析,提升分析速度,
     *               需要runDO,libraryId,rtExtractionWindow,mzExtractionWindow,SlopeIntercept
     */
    public OverviewDO extractRun(RunDO run, AnalyzeParams params) throws XException {
        TaskDO task = params.getTaskDO();
        task.addLog("Start Checking");
        ConvolutionUtil.checkRun(run);
        OverviewDO overview = overviewService.init(run, params);
        if (overview == null) {
            throw new XException(ResultCode.ANALYZE_CREATE_FAILED);
        }
        //核心函数在这里
        extractRun(overview, run, params);
        overviewService.update(overview);
        return overview;
    }

    public Eic extractMS1(PeptideCoord coord, TreeMap<Float, MzIntensityPairs> ms1Map, AnalyzeParams params) {
        float mzStart = 0;
        float mzEnd = -1;
        //所有的碎片共享同一个RT数组
        ArrayList<Float> rtList = new ArrayList<>();
        for (Float rt : ms1Map.keySet()) {
            if (params.getMethod().getEic().getRtWindow() != -1 && rt > coord.getRtEnd()) {
                break;
            }
            if (params.getMethod().getEic().getRtWindow() == -1 || (rt >= coord.getRtStart() && rt <= coord.getRtEnd())) {
                rtList.add(rt);
            }
        }

        float[] rtArray = new float[rtList.size()];
        for (int i = 0; i < rtList.size(); i++) {
            rtArray[i] = rtList.get(i);
        }

        float ppmWindow = params.getMethod().getEic().getMzWindow().floatValue();
        float mz = coord.getMz().floatValue();
        float window = mz * ppmWindow * Constants.PPM_F;
        mzStart = mz - window;
        mzEnd = mz + window;
        float[] intArray = new float[rtArray.length];
        //本函数极其注重性能,为整个流程最关键的耗时步骤,每提升10毫秒都可以带来巨大的性能提升  --陆妙善
        for (int i = 0; i < rtArray.length; i++) {
            float acc = ConvolutionUtil.accumulation(ms1Map.get(rtArray[i]), mzStart, mzEnd);
            intArray[i] = acc;
        }
        return new Eic(rtArray, intArray);
    }

    /**
     * EIC Core Function
     * 核心EIC函数
     * <p>
     * 本函数为整个分析过程中最耗时的步骤
     *
     * @param coord
     * @param ms2Map
     * @param params
     * @return
     */
    public DataDO extract(PeptideCoord coord, TreeMap<Float, MzIntensityPairs> ms1Map, TreeMap<Float, MzIntensityPairs> ms2Map, AnalyzeParams params, boolean withIonCount, Float ionsHighLimit) {
        //所有的碎片共享同一个RT数组
        ArrayList<Float> rtList = new ArrayList<>();
        for (Float rt : ms2Map.keySet()) {
            if (params.getMethod().getEic().getRtWindow() != -1 && rt > coord.getRtEnd()) {
                break;
            }
            if (params.getMethod().getEic().getRtWindow() == -1 || (rt >= coord.getRtStart() && rt <= coord.getRtEnd())) {
                rtList.add(rt);
            }
        }

        float[] rtArray = new float[rtList.size()];
        for (int i = 0; i < rtList.size(); i++) {
            rtArray[i] = rtList.get(i);
        }

        DataDO data = new DataDO(coord);
        data.setRtArray(rtArray);
        if (StringUtils.isNotEmpty(params.getOverviewId())) {
            data.setOverviewId(params.getOverviewId());
            data.setId(params.getOverviewId() + coord.getPeptideRef() + data.getDecoy());            //在此处直接设置data的Id
        }

        boolean isHit = false;
        float ppmWindow = params.getMethod().getEic().getMzWindow().floatValue();
        for (FragmentInfo fi : coord.getFragments()) {
            float[] intArray = acc(fi.getMz().floatValue(), ppmWindow, rtArray, ms2Map, false);
            if (intArray == null) {//如果该cutInfo没有XIC到任何数据,则不存入IntMap中,这里专门写这个if逻辑是为了帮助后续阅读代码的时候更加容易理解.我们在这边是特地没有将未检测到的碎片放入map的
                continue;
            } else {
                isHit = true;
                data.getIntMap().put(fi.getCutInfo(), intArray); //记录每一个碎片的光谱图
            }
        }

        //提取self mz
        float[] selfIntArray = acc(coord.getMz().floatValue(), ppmWindow, rtArray, ms2Map, true);
        data.setSelfInts(selfIntArray);

        //如果所有的片段均没有提取到XIC的结果,则直接返回null
        if (!isHit) {
            return null;
        }

        //计算每一帧的离子碎片总数
        if (withIonCount) {
            calcIonsCount(data, coord, ms2Map, params.getMethod().getEic().getIonsLow(), ionsHighLimit == null ? params.getMethod().getEic().getIonsHigh() : ionsHighLimit);
        }
        //ms1数据不为空的时候需要增加ms1谱图
        if (ms1Map != null) {
            Eic eic = extractMS1(coord, ms1Map, params);
            if (data.getRtArray().length > eic.rts().length) {
                data.setMs1Ints(ArrayUtil.add(eic.ints(), 0));
            } else if (data.getRtArray().length < eic.rts().length) {
                data.setMs1Ints(Arrays.copyOfRange(eic.ints(), 0, eic.ints().length - 1));
            } else {
                data.setMs1Ints(eic.ints());
            }
        }
        return data;
    }

    /**
     * 根据coord肽段坐标读取run对应的aird文件中涉及的相关光谱图
     *
     * @param run
     * @return
     */
    public TreeMap<Float, MzIntensityPairs> getMS1Map(RunDO run) throws XException {
        ConvolutionUtil.checkRun(run);

        Compressor mzCompressor = run.fetchCompressor(Compressor.TARGET_MZ);
        Compressor intCompressor = run.fetchCompressor(Compressor.TARGET_INTENSITY);

        //Step1.获取窗口信息
        TreeMap<Float, MzIntensityPairs> ms1Map;
        BlockIndexDO index = blockIndexService.getMS1(run.getId());
        if (index == null) {
            throw new XException(ResultCode.BLOCK_INDEX_NOT_EXISTED);
        }
        DIAParser parser = null;
        try {
            parser = new DIAParser(run.getAirdPath(), mzCompressor, intCompressor, mzCompressor.getPrecision());
            ms1Map = parser.getSpectrums(index.getStartPtr(), index.getEndPtr(), index.getRts(), index.getMzs(), index.getInts());
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new XException(ResultCode.PARSE_MS1_SPECTRUM_FAILED);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
        return ms1Map;
    }

    /**
     * 根据coord肽段坐标读取run对应的aird文件中涉及的相关光谱图
     *
     * @param run
     * @param coord
     * @return
     */
    public TreeMap<Float, MzIntensityPairs> getMS1Map(RunDO run, PeptideCoord coord) throws XException {
        ConvolutionUtil.checkRun(run);
        Compressor mzCompressor = run.fetchCompressor(Compressor.TARGET_MZ);
        Compressor intCompressor = run.fetchCompressor(Compressor.TARGET_INTENSITY);

        //Step1.获取窗口信息
        TreeMap<Float, MzIntensityPairs> ms1Map;
        BlockIndexDO index = blockIndexService.getMS1(run.getId());
        if (index == null) {
            throw new XException(ResultCode.BLOCK_INDEX_NOT_EXISTED);
        }
        DIAParser parser = null;
        try {
            parser = new DIAParser(run.getAirdPath(), mzCompressor, intCompressor, mzCompressor.getPrecision());
            ms1Map = parser.getSpectrumsByRtRange(index.getStartPtr(), index.getRts(), index.getMzs(), index.getInts(), (float) coord.getRtStart(), (float) coord.getRtEnd());
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new XException(ResultCode.PARSE_ERROR);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
        return ms1Map;
    }

    /**
     * 根据coord肽段坐标读取run对应的aird文件中涉及的相关光谱图
     *
     * @param run
     * @param coord
     * @return
     */
    public TreeMap<Float, MzIntensityPairs> getMS2Map(RunDO run, PeptideCoord coord) throws XException {
        ConvolutionUtil.checkRun(run);
        Compressor mzCompressor = run.fetchCompressor(Compressor.TARGET_MZ);
        Compressor intCompressor = run.fetchCompressor(Compressor.TARGET_INTENSITY);

        //Step1.获取窗口信息
        TreeMap<Float, MzIntensityPairs> ms2Map;
        BlockIndexDO index = blockIndexService.getMS2(run.getId(), coord.getMz());
        if (index == null) {
            throw new XException(ResultCode.BLOCK_INDEX_NOT_EXISTED);
        }
        DIAParser parser = null;
        try {
            parser = new DIAParser(run.getAirdPath(), mzCompressor, intCompressor, mzCompressor.getPrecision());
            ms2Map = parser.getSpectrumsByRtRange(index.getStartPtr(), index.getRts(), index.getMzs(), index.getInts(), (float) coord.getRtStart(), (float) coord.getRtEnd());
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new XException(ResultCode.PARSE_ERROR);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
        return ms2Map;
    }

    /**
     * 实时提取某一个PeptideRef的XIC图谱
     * 其中Run如果没有包含irt结果,则会进行全rt进行搜索
     * 不适合用于大批量处理
     *
     * @param run
     * @param coord
     * @return
     */
    public Result<RunDataVO> predictOne(RunDO run, OverviewDO overview, PeptideCoord coord, AnalyzeParams params) throws XException {
        Double rt = coord.getRt();
        if (params.getMethod().getEic().getRtWindow() == -1) {
            coord.setRtRange(-1, 99999);
        } else {
            double targetRt = run.getIrt().getSi().realRt(rt);
            coord.setRtRange(targetRt - 300, targetRt + 300);
        }
        TreeMap<Float, MzIntensityPairs> ms1Map = getMS1Map(run, coord);
        TreeMap<Float, MzIntensityPairs> ms2Map = getMS2Map(run, coord);
        AnyPair<DataDO, DataSumDO> dataPair = coreFunc.predictOneNiubi(coord, ms1Map, ms2Map, run, overview, params);
//        AnyPair<DataDO, DataSumDO> dataPair = coreFunc.predictOneDelete(coord, ms2Result.getData(), run, overview, params);
        if (dataPair == null) {
            return Result.Error(ResultCode.ANALYSE_DATA_ARE_ALL_ZERO);
        }
        RunDataVO runDataVO = new RunDataVO().merge(dataPair.getLeft(), dataPair.getRight());
        if (runDataVO == null) {
            return Result.Error(ResultCode.ANALYSE_DATA_ARE_ALL_ZERO);
        }

        return Result.OK(runDataVO);
    }

    /**
     * 需要传入最终结果集的List对象
     * 最终的XIC结果存储在内存中不落盘,一般用于iRT的计算
     * 由于是直接在内存中的,所以XIC的结果不进行压缩
     *
     * @param finalList
     * @param coordinates
     * @param ms2Map
     * @param params
     */
    public void extract4Irt(List<DataDO> finalList, List<PeptideCoord> coordinates, TreeMap<Float, MzIntensityPairs> ms2Map, AnalyzeParams params) {
        for (PeptideCoord coord : coordinates) {
            DataDO data = extract(coord, null, ms2Map, params, true, null);
            if (data != null) {
                finalList.add(data);
            }
        }
    }


    /**
     * 提取MS2 XIC图谱并且输出最终结果,不返回最终的XIC结果以减少内存的使用
     *
     * @param overviewDO
     * @param run
     * @param params
     */
    private void extractRun(OverviewDO overviewDO, RunDO run, AnalyzeParams params) {
        TaskDO task = params.getTaskDO();
        //Step1.获取窗口信息
        List<WindowRange> ranges = run.getWindowRanges();
        BlockIndexQuery query = new BlockIndexQuery(run.getId(), 2);

        //获取所有MS2的窗口
        List<BlockIndexDO> blockIndexList = blockIndexService.getAll(query);
        blockIndexList = blockIndexList.stream().sorted(Comparator.comparing(blockIndexDO -> blockIndexDO.getRange().getStart())).toList();
        task.addLog("Total Windows:" + ranges.size() + ",Start XIC processing");
        taskService.update(task);
        //按窗口开始扫描.如果一共有N个窗口,则一共分N个批次进行XIC提取
        int count = 1;
        DIAParser parser = null;
        try {
            parser = new DIAParser(run.getAirdIndexPath());
            long peakCount = 0L;
            int dataCount = 0;
            TreeMap<Float, MzIntensityPairs> ms1Map = getMS1Map(run);

            for (BlockIndexDO index : blockIndexList) {

                long start = System.currentTimeMillis();
                task.addLog("Processing:" + index.getRange().getStart() + "-" + index.getRange().getEnd() + ",Current:" + count + "/" + blockIndexList.size());
                //构建坐标
                List<PeptideCoord> coords = peptideService.buildCoord(params.getAnaLibId(), index.getRange(), params.getMethod().getEic().getRtWindow(), run.getIrt().getSi());
                if (coords.isEmpty()) {
                    task.addLog("No Coordinates Found,Rang:" + index.getRange().getStart() + ":" + index.getRange().getEnd());
                    taskService.update(task);
                    continue;
                }

                //Step3.提取指定原始谱图
                List<DataDO> dataList = null;
                TreeMap<Float, MzIntensityPairs> ms2Map = parser.getSpectrums(index.getStartPtr(), index.getEndPtr(), index.getRts(), index.getMzs(), index.getInts());
                if (params.getReselect()) {
                    dataList = coreFunc.reselect(run, coords, ms1Map, ms2Map, params);
                } else {
                    dataList = coreFunc.csi(run, coords, ms1Map, ms2Map, params);
                }

                if (dataList != null) {
                    peakCount += dataList.stream().filter(data -> data.getPeakGroupList() != null).mapToInt(data -> data.getPeakGroupList().size()).sum();
                    dataCount += dataList.size();
                } else {
                    task.addLog("Analysis Data is empty");
                }
                dataService.insert(dataList, overviewDO.getProjectId());
                task.addLog("(" + count + "-[" + index.getRange().getStart() + "," + index.getRange().getEnd() + "])XIC Finished,Effective Peptides:" + (dataList == null ? 0 : dataList.size()) + ",Time Cost:" + (System.currentTimeMillis() - start) / 1000 + "s");
                taskService.update(task);
                count++;
            }

            task.addLog("Total Peptide Count:" + dataCount + ",Total Peak Count:" + peakCount);
            overviewDO.getStatistic().put(StatConst.TOTAL_PEPTIDE_COUNT, dataCount);
            overviewDO.getStatistic().put(StatConst.TOTAL_PEAK_COUNT, peakCount);
            overviewService.update(overviewDO);
        } catch (XException xe) {
            xe.printStackTrace();
            task.finish(TaskStatus.FAILED.getName(), xe.getErrorMsg());
            taskService.update(task);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    public void calcIonsCount(DataDO dataDO, PeptideCoord coord, TreeMap<Float, MzIntensityPairs> rtMap, Float ionsLowLimit, Float ionsHighLimit) {
        String maxIon = coord.getFragments().get(0).getCutInfo();
        int[] ionsLow = new int[dataDO.getRtArray().length];
        int[] ionsHigh = new int[dataDO.getRtArray().length];
        for (int i = 0; i < dataDO.getRtArray().length; i++) {
            MzIntensityPairs pairs = rtMap.get(dataDO.getRtArray()[i]);
            float[] maxIntensities = dataDO.getIntMap().get(maxIon); //获取该spectrum中maxIon的强度列表
            float maxIonIntensityInThisSpectrum = 0;
            if (maxIntensities == null || maxIntensities.length == 0) {
                maxIonIntensityInThisSpectrum = Float.MAX_VALUE;
            } else {
                maxIonIntensityInThisSpectrum = maxIntensities[i];
            }

            IntegerPair pair = diaScorer.calcTotalIons(pairs,
                    coord.getUnimodMap(),
                    coord.getSequence(),
                    coord.getCharge(),
                    ionsLowLimit,
                    ionsHighLimit,
                    maxIonIntensityInThisSpectrum);
            ionsLow[i] = pair.left();
            ionsHigh[i] = pair.right();
        }

        dataDO.setIonsLow(ionsLow);
        dataDO.setIonsHigh(ionsHigh);
    }

    /**
     * EIC一个mz,如果所有的光谱图上信号都为0,则返回null
     * 本函数极其注重性能,为整个流程最关键的耗时步骤,每提升10毫秒都可以带来巨大的性能提升  --陆妙善
     *
     * @param mz
     * @param ppm
     * @param rtArray
     * @param msMap
     * @return
     */
    private float[] acc(float mz, float ppm, float[] rtArray, TreeMap<Float, MzIntensityPairs> msMap, boolean withZero) {
        float window = mz * ppm * Constants.PPM_F;
        float mzStart = mz - window;
        float mzEnd = mz + window;
        float[] intArray = new float[rtArray.length];
        boolean isAllZero = true;
        for (int i = 0; i < rtArray.length; i++) {
            float acc = ConvolutionUtil.accumulation(msMap.get(rtArray[i]), mzStart, mzEnd);
            intArray[i] = acc;
            if (acc != 0) {
                isAllZero = false;
            }
        }
        if (isAllZero && !withZero) {
            return null;
        } else {
            return intArray;
        }
    }
}
