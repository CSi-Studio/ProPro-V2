package net.csibio.propro.algorithm.parser.model.mzxml;

import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamConverter;
import lombok.Data;
import net.csibio.propro.algorithm.parser.xml.PeaksConverter;

@Data
@XStreamConverter(PeaksConverter.class)
public class Peaks {

    byte[] value;

    @XStreamAsAttribute
    protected Integer precision;

    @XStreamAsAttribute
    String byteOrder;

    @XStreamAsAttribute
    String contentType;

    @XStreamAsAttribute
    String compressionType;

    @XStreamAsAttribute
    Integer compressedLen;
}
