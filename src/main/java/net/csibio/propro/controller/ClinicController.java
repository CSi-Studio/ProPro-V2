package net.csibio.propro.controller;

import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;
import net.csibio.aird.bean.WindowRange;
import net.csibio.propro.algorithm.learner.classifier.Lda;
import net.csibio.propro.algorithm.peak.GaussFilter;
import net.csibio.propro.algorithm.peak.SignalToNoiseEstimator;
import net.csibio.propro.algorithm.score.scorer.Scorer;
import net.csibio.propro.algorithm.stat.StatConst;
import net.csibio.propro.constants.enums.IdentifyStatus;
import net.csibio.propro.constants.enums.ResultCode;
import net.csibio.propro.domain.Result;
import net.csibio.propro.domain.bean.common.FloatPairs;
import net.csibio.propro.domain.bean.common.IdName;
import net.csibio.propro.domain.bean.common.IdNameAlias;
import net.csibio.propro.domain.bean.common.PeptideRtPairs;
import net.csibio.propro.domain.bean.data.PeptideRt;
import net.csibio.propro.domain.bean.method.Method;
import net.csibio.propro.domain.bean.overview.Overview4Clinic;
import net.csibio.propro.domain.bean.peptide.FragmentInfo;
import net.csibio.propro.domain.db.*;
import net.csibio.propro.domain.options.SigmaSpacing;
import net.csibio.propro.domain.query.*;
import net.csibio.propro.domain.vo.ClinicPrepareDataVO;
import net.csibio.propro.domain.vo.RunDataVO;
import net.csibio.propro.service.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Api(tags = {"Clinic Module"})
@RestController
@RequestMapping("/api/clinic/")
public class ClinicController {

  @Autowired TaskService taskService;
  @Autowired LibraryService libraryService;
  @Autowired ProjectService projectService;
  @Autowired MethodService methodService;
  @Autowired RunService runService;
  @Autowired OverviewService overviewService;
  @Autowired DataService dataService;
  @Autowired DataSumService dataSumService;
  @Autowired SignalToNoiseEstimator signalToNoiseEstimator;
  @Autowired PeptideService peptideService;
  @Autowired BlockIndexService blockIndexService;
  @Autowired Lda lda;
  @Autowired Scorer scorer;
  @Autowired GaussFilter gaussFilter;

  @GetMapping(value = "prepare")
  Result<ClinicPrepareDataVO> prepare(
      @RequestParam(value = "projectId") String projectId,
      @RequestParam(value = "overviewIds", required = false) List<String> overviewIds) {
    ProjectDO project = projectService.getById(projectId);
    if (project == null) {
      return Result.Error(ResultCode.PROJECT_NOT_EXISTED);
    }
    if (StringUtils.isEmpty(project.getInsLibId())
        || StringUtils.isEmpty(project.getAnaLibId())
        || StringUtils.isEmpty(project.getMethodId())) {
      return Result.Error(ResultCode.INS_ANA_METHOD_ID_CANNOT_BE_EMPTY_WHEN_USING_CLINIC);
    }
    LibraryDO anaLib = libraryService.getById(project.getAnaLibId());
    if (anaLib == null) {
      return Result.Error(ResultCode.ANA_LIBRARY_NOT_EXISTED);
    }
    LibraryDO insLib = libraryService.getById(project.getInsLibId());
    if (insLib == null) {
      return Result.Error(ResultCode.INS_LIBRARY_NOT_EXISTED);
    }
    // TODO 王嘉伟 当overviewIds不为空的时候,检测他们的projectId, methodId, insId, anaId是否一致

    List<IdNameAlias> runList = null;
    List<Overview4Clinic> totalOverviewList = null;
    if (overviewIds == null || overviewIds.size() == 0) {
      // 如果overviewIds不为空,则按照overviewIds获取,否则获取该项目下所有的defaultOne=true的overview
      runList = runService.getAll(new RunQuery().setProjectId(projectId), IdNameAlias.class);
      totalOverviewList =
          overviewService.getAll(
              new OverviewQuery(projectId).setDefaultOne(true), Overview4Clinic.class);
    } else {
      totalOverviewList =
          overviewService.getAll(
              new OverviewQuery(projectId).setIds(overviewIds), Overview4Clinic.class);
      List<String> runIds =
          totalOverviewList.stream().map(Overview4Clinic::getRunId).collect(Collectors.toList());
      runList =
          runService.getAll(
              new RunQuery().setProjectId(projectId).setIds(runIds), IdNameAlias.class);
    }
    Map<String, List<Overview4Clinic>> overviewMap =
        totalOverviewList.stream().collect(Collectors.groupingBy(Overview4Clinic::getRunId));
    ClinicPrepareDataVO data = new ClinicPrepareDataVO();
    data.setProject(project);
    if (runList.stream().filter(idNameAlias -> idNameAlias.alias() != null).count()
        == runList.size()) {
      data.setRunList(
          runList.stream()
              .sorted(Comparator.comparing(IdNameAlias::alias))
              .collect(Collectors.toList()));
    } else {
      data.setRunList(
          runList.stream()
              .sorted(Comparator.comparing(IdNameAlias::name))
              .collect(Collectors.toList()));
    }
    data.setInsLib(new IdName(insLib.getId(), insLib.getName()));
    data.setAnaLib(new IdName(anaLib.getId(), anaLib.getName()));

    Method method = null;
    if (totalOverviewList.size() > 0) {
      method = overviewService.getById(totalOverviewList.get(0).getId()).getParams().getMethod();
    }
    if (method == null) {
      Result.Error(ResultCode.METHOD_NOT_EXISTED);
    }
    data.setMethod(method);
    data.setProteins(anaLib.getProteins());
    data.setOverviewMap(overviewMap);
    if (anaLib.getStatistic().get(StatConst.Peptide_Count) != null) {
      data.setPeptideCount((Long) anaLib.getStatistic().get(StatConst.Peptide_Count));
    }
    if (anaLib.getStatistic().get(StatConst.Protein_Count) != null) {
      data.setProteinCount((Long) anaLib.getStatistic().get(StatConst.Protein_Count));
    }

    return Result.OK(data);
  }

  /**
   * Core API 1. If the EIC data exist. Get the data directly from the database 2. Else predict the
   * Y-Ion for the target peptide and analyze the EIC data from the Aird file
   *
   * @param projectId
   * @param libraryId
   * @param peptideRef
   * @param predict
   * @param smooth
   * @param denoise
   * @param overviewIds
   * @return
   */
  @PostMapping(value = "/getRunData")
  Result<List<RunDataVO>> getRunData(
      @RequestParam("projectId") String projectId,
      @RequestParam(value = "libraryId", required = false) String libraryId,
      @RequestParam("peptideRef") String peptideRef,
      @RequestParam("predict") Boolean predict,
      @RequestParam(value = "changeCharge", required = false) Boolean changeCharge,
      @RequestParam(value = "smooth", required = false) Boolean smooth,
      @RequestParam(value = "denoise", required = false) Boolean denoise,
      @RequestParam("overviewIds") List<String> overviewIds) {
    log.info(
        "开始获取新预测数据-------------------------------------------------------------------------------");
    List<RunDataVO> dataList = new ArrayList<>();
    PeptideDO peptide =
        peptideService.getOne(
            new PeptideQuery().setLibraryId(libraryId).setPeptideRef(peptideRef), PeptideDO.class);

    for (int i = 0; i < overviewIds.size(); i++) {
      String overviewId = overviewIds.get(i);
      OverviewDO overview = overviewService.getById(overviewId);
      if (overview == null) {
        continue;
      }
      RunDataVO data = null;
      // 如果使用预测方法,则进行实时EIC获取
      if (predict) {
        RunDO run = runService.getById(overview.getRunId());
        //                DataSumDO existed = dataSumService.getOne(new
        // DataSumQuery().setOverviewId(overview.getId()).setPeptideRef(peptideRef).setDecoy(false),
        // DataSumDO.class, projectId);
        //                if (existed.getStatus() == IdentifyStatus.SUCCESS.getCode()) {
        //                    DataDO existedData = dataService.getById(existed.getId(), projectId);
        //                    DataSumDO dataSum = scorer.calcBestTotalScore(existedData, overview,
        // null);
        //                    data = new ExpDataVO().merge(existedData, dataSum);
        //                    data.setGroup(run.getGroup());
        //                    data.setAlias(run.getAlias());
        //                    data.setExpId(run.getId());
        //                } else {
        //                peptide.setFragments(peptide.getFragments().stream().filter(f ->
        // !f.getCutInfo().equals("y14^2")).toList());
        Result<RunDataVO> res =
            dataService.predictDataFromFile(run, peptide, changeCharge, overview.getId());
        if (res.isSuccess()) {
          data = res.getData();
          data.setGroup(run.getGroup());
          data.setAlias(run.getAlias());
          data.setRunId(run.getId());
          data.setOverviewId(overviewId);
        }
        //                }
      } else {
        data =
            dataService.getDataFromDB(projectId, overview.getRunId(), overview.getId(), peptideRef);
      }
      if (data != null) {
        if (data.getStatus() == null) {
          data.setStatus(IdentifyStatus.FAILED.getCode());
        }
        data.setMinTotalScore(overview.getMinTotalScore());
        lda.scoreForPeakGroups(
            data.getPeakGroupList(),
            overview.getWeights(),
            overview.getParams().getMethod().getScore().getScoreTypes());
        dataList.add(data);
      }
    }

    if (dataList.size() == 0) {
      return Result.Error(ResultCode.DATA_IS_EMPTY);
    }

    if (smooth) {
      SigmaSpacing ss = SigmaSpacing.create();
      dataList.forEach(
          data -> {
            HashMap<String, float[]> smoothInt =
                gaussFilter.filter(
                    data.getRtArray(), (HashMap<String, float[]>) data.getIntMap(), ss);
            data.setIntMap(smoothInt);
          });
    }

    if (denoise) {
      dataList.forEach(
          data -> {
            HashMap<String, float[]> denoiseIntMap = new HashMap<>();
            float[] rt = data.getRtArray();
            for (String cutInfo : data.getIntMap().keySet()) {
              double[] noises200 =
                  signalToNoiseEstimator.computeSTN(rt, data.getIntMap().get(cutInfo), 200, 30);
              float[] denoiseInt = new float[noises200.length];
              for (int i = 0; i < noises200.length; i++) {
                denoiseInt[i] =
                    (float) (data.getIntMap().get(cutInfo)[i] * noises200[i] / (noises200[i] + 1));
              }
              denoiseIntMap.put(cutInfo, denoiseInt);
            }
            data.setIntMap(denoiseIntMap);
          });
    }

    Result<List<RunDataVO>> result = new Result<List<RunDataVO>>(true);
    result.setData(dataList);
    Map<String, Double> intensityMap =
        peptide.getFragments().stream()
            .collect(Collectors.toMap(FragmentInfo::getCutInfo, FragmentInfo::getIntensity));
    result.getFeatureMap().put("intensityMap", intensityMap);
    return result;
  }

  @PostMapping(value = "/getSpectra")
  Result<FloatPairs> getSpectra(
      @RequestParam(value = "runId", required = false) String runId,
      @RequestParam(value = "mz", required = false) Double mz,
      @RequestParam(value = "rt", required = false) Float rt) {
    RunDO run = runService.getById(runId);
    FloatPairs pairs = runService.getSpectrum(run, mz, rt);
    return Result.OK(pairs);
  }

  @PostMapping(value = "/getRtPairs")
  Result<HashMap<String, PeptideRtPairs>> getRtPairs(
      @RequestParam("projectId") String projectId,
      @RequestParam("onlyDefault") Boolean onlyDefault,
      @RequestParam("runIds") List<String> runIds,
      @RequestParam(value = "mz", required = false) Double mz) {
    HashMap<String, PeptideRtPairs> map = new HashMap<>();
    long start = System.currentTimeMillis();
    for (int i = 0; i < runIds.size(); i++) {
      String runId = runIds.get(i);
      RunDO run = runService.getById(runId);
      Set<Float> limitRts = new HashSet<Float>();
      if (mz != null) {
        List<WindowRange> ranges = run.getWindowRanges();
        for (WindowRange range : ranges) {
          if (range.getStart() <= mz && range.getEnd() > mz) {
            BlockIndexDO index = blockIndexService.getOne(runId, range.getMz());
            limitRts = new HashSet<>(index.getRts());
          }
        }
      }

      OverviewQuery query = new OverviewQuery(projectId).setRunId(runId);
      if (onlyDefault) {
        query.setDefaultOne(true);
      }
      Overview4Clinic overview = overviewService.getOne(query, Overview4Clinic.class);
      if (overview == null) {
        continue;
      }

      List<PeptideRt> realRtList =
          dataSumService.getAll(
              new DataSumQuery(overview.getId())
                  .setDecoy(false)
                  .setStatus(IdentifyStatus.SUCCESS.getCode())
                  .setIsUnique(true),
              PeptideRt.class,
              projectId);

      if (limitRts.size() != 0) {
        Set<Float> finalLimitRts = limitRts;
        realRtList =
            realRtList.stream()
                .filter(p -> finalLimitRts.contains(p.apexRt().floatValue()))
                .toList();
      }
      List<String> ids = realRtList.stream().map(PeptideRt::id).collect(Collectors.toList());
      if (ids.size() == 0) {
        log.error("没有找到任何鉴定到的数据");
        continue;
      }
      long s = System.currentTimeMillis();
      List<PeptideRt> libRtList =
          dataService.getAll(
              new DataQuery(overview.getId()).setIds(ids), PeptideRt.class, projectId);
      if (realRtList.size() != libRtList.size()) {
        log.error("数据异常,LibRt Size:" + libRtList.size() + ",RealRt Size:" + realRtList.size());
        continue;
      }
      log.info("数据库读取耗时:" + (System.currentTimeMillis() - s));
      Map<String, Double> libRtMap =
          libRtList.stream().collect(Collectors.toMap(PeptideRt::peptideRef, PeptideRt::libRt));
      // 横坐标是libRt,纵坐标是realRt
      String[] peptideRefs = new String[realRtList.size()];
      double[] x = new double[realRtList.size()];
      double[] y = new double[realRtList.size()];
      double[] libRts = new double[realRtList.size()];
      realRtList =
          realRtList.stream()
              .sorted(Comparator.comparingDouble(PeptideRt::apexRt))
              .collect(Collectors.toList());
      for (int j = 0; j < realRtList.size(); j++) {
        peptideRefs[j] = realRtList.get(j).peptideRef();
        //                x[j] = libRtMap.get(peptideRefs[j]);
        x[j] = realRtList.get(j).apexRt();
        y[j] =
            realRtList.get(j).apexRt() - run.getIrt().getSi().realRt(libRtMap.get(peptideRefs[j]));
        libRts[j] = realRtList.get(j).libRt();
      }
      map.put(runId, new PeptideRtPairs(peptideRefs, x, y, libRts));
    }
    log.info("rt坐标已渲染,耗时:" + (System.currentTimeMillis() - start));
    return Result.OK(map);
  }
}
