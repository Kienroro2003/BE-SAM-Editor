package com.sam.besameditor.coverage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface CoverageReportParser {

    Map<String, List<CoverageLineStat>> parse(Path reportPath);
}
