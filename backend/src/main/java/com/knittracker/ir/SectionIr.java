package com.knittracker.ir;

import java.util.List;

public record SectionIr(String nameKey, String nameSrc, List<StepIr> steps) {}
