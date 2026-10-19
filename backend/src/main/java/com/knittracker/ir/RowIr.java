package com.knittracker.ir;

import java.util.List;

public record RowIr(Side side, String nameKey, String nameSrc, List<SegmentIr> segments) {

    public enum Side { RS, WS }
}
