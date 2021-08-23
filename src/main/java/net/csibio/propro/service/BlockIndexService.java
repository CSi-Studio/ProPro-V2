package net.csibio.propro.service;

import net.csibio.aird.bean.MzIntensityPairs;
import net.csibio.propro.domain.db.BlockIndexDO;
import net.csibio.propro.domain.query.BlockIndexQuery;

import java.util.List;
import java.util.TreeMap;

public interface BlockIndexService extends BaseService<BlockIndexDO, BlockIndexQuery> {

    List<BlockIndexDO> getAllByExpId(String expId);

    List<BlockIndexDO> getAllMS2ByExpId(String expId);

    MzIntensityPairs getNearestSpectrumByRt(TreeMap<Float, MzIntensityPairs> rtMap, Double rt);

    BlockIndexDO getOne(String expId, Float mz);

    List<BlockIndexDO> getLinkedBlockIndex(String expId, Float mz, Double deltaMz, Integer collectedNumber);
}
