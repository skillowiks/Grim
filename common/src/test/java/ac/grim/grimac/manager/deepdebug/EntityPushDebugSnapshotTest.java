package ac.grim.grimac.manager.deepdebug;

import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.math.Vector3dm;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPushDebugSnapshotTest {
    @Test
    void capsCacheReadsEvenWhenNoEntityIsNear() {
        AtomicInteger reads = new AtomicInteger();
        String snapshot = EntityPushDebugSnapshot.scanNearby(IntStream.range(0, 10000).iterator(), entity -> {
            reads.incrementAndGet();
            return false;
        }, entity -> { throw new AssertionError("Distant entities must not be described"); });
        assertEquals(256, reads.get());
        assertTrue(snapshot.contains("scanned=256,scanTruncated=true,nearInScan=0,omittedNear=0"));
    }

    @Test
    void independentlyCapsDescriptionsAndMarksNearbyOmissions() {
        AtomicInteger descriptions = new AtomicInteger();
        String snapshot = EntityPushDebugSnapshot.scanNearby(IntStream.range(0, 300).iterator(), entity -> true, entity -> {
            descriptions.incrementAndGet();
            return "{id=" + entity + "}";
        });
        assertEquals(8, descriptions.get());
        assertTrue(snapshot.contains("scanned=256,scanTruncated=true,nearInScan=256,omittedNear=248"));
        assertTrue(snapshot.contains("{id=7}"));
    }

    @Test
    void completedSparseScanPreservesSelectedEntities() {
        String snapshot = EntityPushDebugSnapshot.scanNearby(List.of(1, 2, 3).iterator(), entity -> entity != 2,
                entity -> "{id=" + entity + "}");
        assertEquals("{scanned=3,scanTruncated=false,nearInScan=2,omittedNear=0,entries=[{id=1};{id=3}]}", snapshot);
    }

    @Test
    void failedCacheReadIsUnavailableRatherThanAnEmptySuccess() {
        String snapshot = EntityPushDebugSnapshot.scanNearby(List.of(1, 2).iterator(), entity -> {
            if (entity == 2) throw new IllegalStateException("sensitive detail must not appear");
            return true;
        }, entity -> "{id=" + entity + "}");
        assertEquals("{scanned=2,unavailable=IllegalStateException,partialEntries=[{id=1}]}", snapshot);
    }

    @Test
    void historiesReportValuesAndMaximumRatherThanQueueLength() {
        assertEquals("{max=0,oldestFirst=[0, 0, 0]}", EntityPushDebugSnapshot.history(List.of(0, 0, 0)));
        assertEquals("{max=2,oldestFirst=[0, 2, 1]}", EntityPushDebugSnapshot.history(List.of(0, 2, 1)));
        assertEquals("{max=0.1,oldestFirst=[0.0, 0.1, 0.0]}", EntityPushDebugSnapshot.history(List.of(0.0, 0.1, 0.0)));
        assertEquals("{max=none,oldestFirst=[]}", EntityPushDebugSnapshot.history(List.of()));
    }

    @Test
    void preservesInputEvidenceAfterMovementTickerRebuildsBestVelocity() {
        VectorData input = new VectorData(new Vector3dm(1, 2, 3), VectorData.VectorType.InputResult);
        VectorData selected = input.returnNewModified(new Vector3dm(4, 5, 6), VectorData.VectorType.BestVelPicked);
        selected.preUncertainty = input;
        assertEquals("{source=explicit,vector=1.0,2.0,3.0}", EntityPushDebugSnapshot.describePreUncertainty(selected));
        VectorData endpoint = new VectorData(selected.vector.clone(), selected.lastVector, selected.vectorType);
        assertEquals("{source=bestCandidate,vector=1.0,2.0,3.0}", EntityPushDebugSnapshot.describePreUncertainty(endpoint));
        assertEquals("none", EntityPushDebugSnapshot.describePreUncertainty(input));
        assertEquals("none", EntityPushDebugSnapshot.describePreUncertainty(null));
    }
}
