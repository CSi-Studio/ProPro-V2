package net.csibio.propro.utils;

import lombok.extern.slf4j.Slf4j;
import net.csibio.propro.domain.db.DataDO;

@Slf4j
public class AnalyseUtil {

    public static void compress(DataDO data) {
        data.setRtArray(null);
        data.setIntensityMap(null);
    }

    public static String getDataRef(String overviewId, String peptideRef, Boolean decoy) {
        return overviewId + "-" + peptideRef + "-" + decoy;
    }
//    public static void compress(AnalyseDataDO data) {
//        if (data.getRtArray() != null) {
//            data.setRtStart(data.getRtArray()[0]);
//            data.setRtEnd(data.getRtArray()[data.getRtArray().length - 1]);
////            data.setConvRtArray(CompressUtil.zlibCompress(CompressUtil.transToByte(ArrayUtils.toPrimitive(data.getRtArray()))));
//            data.setRtArray(null);
//        }
//        data.setConvIntensityMap(new HashMap<>());
//        for (String cutInfo : data.getIntensityMap().keySet()) {
//            Float[] intensities = data.getIntensityMap().get(cutInfo);
//            if (intensities != null) {
//                data.getConvIntensityMap().put(cutInfo, CompressUtil.zlibCompress(CompressUtil.transToByte(ArrayUtils.toPrimitive(intensities))));
//            } else {
//                data.getConvIntensityMap().put(cutInfo, null);
//            }
//        }
//        data.setIntensityMap(null);
//        data.setCompressed(true);
//    }
//
//
//    public static void decompress(AnalyseDataDO data, List<Float> rtList) {
//        int indexStart = rtList.indexOf(data.getRtStart());
//        int indexEnd = rtList.indexOf(data.getRtEnd());
//
//        Float[] rtArray = new Float[rtList.size()];
//        rtList.toArray(rtArray);
//        data.setRtArray(ArrayUtils.subarray(rtArray, indexStart, indexEnd+1));
//
//        for (String cutInfo : data.getConvIntensityMap().keySet()) {
//            byte[] intensities = data.getConvIntensityMap().get(cutInfo);
//            if (intensities != null) {
//                data.getIntensityMap().put(cutInfo, CompressUtil.transToFloat(CompressUtil.zlibDecompress(intensities)));
//            } else {
//                data.getIntensityMap().put(cutInfo, null);
//            }
//        }
//        data.setConvIntensityMap(null);
//        data.setCompressed(false);
//    }


}
