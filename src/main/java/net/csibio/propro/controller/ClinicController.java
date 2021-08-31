package net.csibio.propro.controller;

import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;
import net.csibio.propro.constants.enums.ResultCode;
import net.csibio.propro.domain.Result;
import net.csibio.propro.domain.bean.experiment.BaseExp;
import net.csibio.propro.domain.db.DataDO;
import net.csibio.propro.domain.db.LibraryDO;
import net.csibio.propro.domain.db.OverviewDO;
import net.csibio.propro.domain.db.ProjectDO;
import net.csibio.propro.domain.query.DataQuery;
import net.csibio.propro.domain.query.ExperimentQuery;
import net.csibio.propro.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Api(tags = {"Clinic Module"})
@RestController
@RequestMapping("clinic/")
public class ClinicController {

    @Autowired
    LibraryService libraryService;
    @Autowired
    TaskService taskService;
    @Autowired
    ProjectService projectService;
    @Autowired
    ExperimentService experimentService;
    @Autowired
    OverviewService overviewService;
    @Autowired
    DataService dataService;
    @Autowired
    DataSumService dataSumService;

    @GetMapping(value = "prepare")
    Result prepare(@RequestParam("projectId") String projectId) {
        ProjectDO project = projectService.getById(projectId);
        Set<String> proteins = new HashSet<>();
        LibraryDO anaLib = libraryService.getById(project.getAnaLibId());
        LibraryDO insLib = libraryService.getById(project.getInsLibId());
        proteins.addAll(anaLib.getProteins());
        proteins.addAll(insLib.getProteins());
        return Result.OK();
    }

    @GetMapping(value = "getProteins")
    Result<List<String>> getProteins(@RequestParam("libraryId") String libraryId,
                                     @RequestParam("protein") String protein) {
        LibraryDO library = libraryService.getById(libraryId);
        Set<String> proteins = library.getProteins();


        return Result.OK();
    }

    @GetMapping(value = "/list")
    Result list(@RequestParam("projectId") String projectId,
                @RequestParam("expIds") List<String> expIds,
                @RequestParam("protein") String protein,
                @RequestParam("peptide") String peptide
    ) {
        if (expIds == null || expIds.size() == 0) {
            return Result.Error(ResultCode.EXPERIMENT_ID_CANNOT_BE_EMPTY);
        }
        ProjectDO project = projectService.getById(projectId);
        if (project == null) {
            return Result.Error(ResultCode.PROJECT_NOT_EXISTED);
        }
        List<BaseExp> expList = experimentService.getAll(new ExperimentQuery().setIds(expIds), BaseExp.class);
        if (expList.size() != expIds.size()) {
            return Result.Error(ResultCode.SOME_EXPERIMENT_NOT_EXISTED);
        }
        Map<String, OverviewDO> overviewMap = overviewService.getDefaultOverviews(projectId, expIds);
        if (overviewMap.size() != expIds.size()) {
            return Result.Error(ResultCode.SOME_EXPS_HAVE_NO_DEFAULT_OVERVIEW);
        }
        if (overviewMap.values().stream().map(OverviewDO::getAnaLibId).collect(Collectors.toSet()).size() > 1) {
            return Result.Error(ResultCode.OVERVIEWS_MUST_USE_THE_SAME_ANA_LIBRARY_ID);
        }

        LibraryDO anaLibId = libraryService.getById(overviewMap.get(expIds.get(0)).getAnaLibId());

        DataQuery query = new DataQuery();

        Result<List<DataDO>> result = dataService.getList(query, query.getProjectId());
        return result;
    }
}
