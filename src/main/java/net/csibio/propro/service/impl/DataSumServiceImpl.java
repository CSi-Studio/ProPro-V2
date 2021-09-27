package net.csibio.propro.service.impl;

import lombok.extern.slf4j.Slf4j;
import net.csibio.propro.constants.enums.IdentifyStatus;
import net.csibio.propro.dao.BaseMultiDAO;
import net.csibio.propro.dao.DataSumDAO;
import net.csibio.propro.domain.bean.score.FinalPeakGroupScore;
import net.csibio.propro.domain.db.DataSumDO;
import net.csibio.propro.domain.db.OverviewDO;
import net.csibio.propro.domain.query.DataSumQuery;
import net.csibio.propro.exceptions.XException;
import net.csibio.propro.service.DataSumService;
import net.csibio.propro.service.OverviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service("dataSumService")
public class DataSumServiceImpl implements DataSumService {

    @Autowired
    DataSumDAO dataSumDAO;
    @Autowired
    OverviewService overviewService;

    @Override
    public BaseMultiDAO<DataSumDO, DataSumQuery> getBaseDAO() {
        return dataSumDAO;
    }

    @Override
    public void beforeInsert(DataSumDO dataSumDO, String projectId) throws XException {
        //Do Nothing
    }

    @Override
    public void beforeUpdate(DataSumDO dataSumDO, String projectId) throws XException {
        //Do Nothing
    }

    @Override
    public void beforeRemove(String id, String projectId) throws XException {
        //Do Nothing
    }

    @Override
    public void buildDataSumList(List<FinalPeakGroupScore> sfsList, Double fdr, OverviewDO overview, String projectId) {
        List<DataSumDO> sumList = new ArrayList<>();
        sfsList.forEach(sfs -> {
            DataSumDO sum = new DataSumDO();
            sum.setOverviewId(overview.getId());
            sum.setId(sfs.getId());
            sum.setProteins(sfs.getProteins());
            sum.setDecoy(sfs.getDecoy());
            sum.setFdr(sfs.getFdr());
            sum.setQValue(sfs.getQValue());
            sum.setFragIntFeature(sfs.getFragIntFeature());
            sum.setRealRt(sfs.getRt());
            sum.setPeptideRef(sfs.getPeptideRef());
            sum.setSum(sfs.getIntensitySum());
            sum.setTotalScore(sfs.getMainScore());
            if (sfs.getFdr() != null && sfs.getFdr() <= 0.01) {
                sum.setStatus(IdentifyStatus.SUCCESS.getCode());
            } else {
                sum.setStatus(IdentifyStatus.FAILED.getCode());
            }
            sumList.add(sum);
        });
        Optional<DataSumDO> op = sumList.stream().filter(data -> data.getDecoy() && data.getStatus() == 1).min(Comparator.comparing(DataSumDO::getTotalScore));
        Double minTotalScore = -9999d;
        if (op.isPresent()) {
            minTotalScore = op.get().getTotalScore();
        }
        overview.setMinTotalScore(minTotalScore);
        overviewService.update(overview);
        insert(sumList, projectId);
    }

    @Override
    public int countMatchedProteins(String overviewId, String projectId, Boolean needUnique, int hit) {
        DataSumQuery query = new DataSumQuery().setOverviewId(overviewId).addStatus(IdentifyStatus.SUCCESS.getCode()).setIsUnique(needUnique).setDecoy(false);
        List<DataSumDO> sumList = dataSumDAO.getAll(query, projectId);
        HashMap<String, Integer> hitMap = new HashMap<>();
        int total;
        sumList.forEach(sum -> {
            if (sum.getProteins() != null) {
                sum.getProteins().forEach(protein -> {
                    if (hitMap.containsKey(protein)) {
                        hitMap.put(protein, hitMap.get(protein) + 1);
                    } else {
                        hitMap.put(protein, 1);
                    }
                });
            }
        });

        total = (int) hitMap.values().stream().filter(value -> value >= hit).count();
        return total;
    }

    @Override
    public int countMatchedPeptide(String overviewId, String projectId, Boolean needUnique) {
        DataSumQuery query = new DataSumQuery().setOverviewId(overviewId).addStatus(IdentifyStatus.SUCCESS.getCode()).setIsUnique(needUnique).setDecoy(false);
        return (int) dataSumDAO.count(query, projectId);
    }
}
