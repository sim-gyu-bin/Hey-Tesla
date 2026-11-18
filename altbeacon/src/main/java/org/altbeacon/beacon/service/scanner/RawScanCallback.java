package org.altbeacon.beacon.service.scanner;

import android.bluetooth.le.ScanResult;
import java.util.List;

/**
 * Hey Tesla local patch: parser와 무관한 실제 광고 및 native 등록 결과를 노출한다.
 * ScanResult.getTimestampNanos()는 Android의 단조시계 원본이며 wall-clock으로 변환하지 않는다.
 * 각 native ScanCallback은 이 hook의 당시 인스턴스를 캡처한다. 호출 스레드는 보장하지 않는다.
 * Licensed under the Apache License, Version 2.0; see the module LICENSE.
 */
public interface RawScanCallback {
    void onScanResult(ScanResult result);
    void onScanStarted();
    void onBatchScanResults(List<ScanResult> results);
    void onScanFailed(int errorCode);
    void onScanStopped(boolean success);
}
