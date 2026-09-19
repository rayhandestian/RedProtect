package br.net.fabiozumbi12.RedProtect.Bukkit.database;

import br.net.fabiozumbi12.RedProtect.Bukkit.Region;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RegionChunkIndexTest {
    private record Bounds(Region region, int minX, int maxX, int minZ, int maxZ, int priority) {
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= -64 && y <= 128;
        }
    }

    private Bounds region(String name, int minX, int maxX, int minZ, int maxZ, int priority) {
        Region region = mock(Region.class);
        when(region.getName()).thenReturn(name);
        when(region.getMinMbrX()).thenReturn(minX);
        when(region.getMaxMbrX()).thenReturn(maxX);
        when(region.getMinMbrZ()).thenReturn(minZ);
        when(region.getMaxMbrZ()).thenReturn(maxZ);
        when(region.getMinY()).thenReturn(-64);
        when(region.getMaxY()).thenReturn(128);
        when(region.getPrior()).thenReturn(priority);
        return new Bounds(region, minX, maxX, minZ, maxZ, priority);
    }

    private void assertMatchesScan(WorldFlatFileRegionManager manager, List<Bounds> regions, int x, int y, int z) {
        Map<Integer, Region> expected = new TreeMap<>();
        for (Bounds bounds : regions) {
            if (bounds.contains(x, y, z)) expected.put(bounds.priority, bounds.region);
        }
        assertEquals(new HashSet<>(expected.values()), manager.getRegions(x, y, z));
        assertEquals(expected, manager.getGroupRegion(x, y, z));
        assertSame(expected.isEmpty() ? null : expected.get(Collections.max(expected.keySet())), manager.getTopRegion(x, y, z));
        assertSame(expected.isEmpty() ? null : expected.get(Collections.min(expected.keySet())), manager.getLowRegion(x, y, z));
    }

    @Test
    public void allPointLookupsMatchScanAcrossBoundariesAndOverlaps() {
        WorldFlatFileRegionManager manager = new WorldFlatFileRegionManager("test_world");
        List<Bounds> regions = new ArrayList<>();
        Random random = new Random(2718);
        for (int i = 0; i < 12; i++) {
            int x = random.nextInt(128) - 64;
            int z = random.nextInt(128) - 64;
            Bounds bounds = region("region_" + i, x, x + 32, z, z + 48, i - 6);
            regions.add(bounds);
            manager.add(bounds.region);
        }
        for (Bounds bounds : regions) {
            for (int x : new int[]{bounds.minX - 1, bounds.minX, bounds.maxX, bounds.maxX + 1}) {
                for (int z : new int[]{bounds.minZ - 1, bounds.minZ, bounds.maxZ, bounds.maxZ + 1}) {
                    for (int y : new int[]{-65, -64, 128, 129}) assertMatchesScan(manager, regions, x, y, z);
                }
            }
        }
        for (int i = 0; i < 200; i++) {
            assertMatchesScan(manager, regions, random.nextInt(192) - 96, 64, random.nextInt(192) - 96);
        }
    }

    @Test(timeout = 10000)
    public void hugeRegionsRemainQueryableWithoutIndexingEveryChunk() {
        WorldFlatFileRegionManager manager = new WorldFlatFileRegionManager("test_world");
        Bounds huge = region("huge", -30000000, 30000000, -30000000, 30000000, -1);
        Bounds local = region("local", -16, 15, -16, 15, 2);
        manager.add(huge.region);
        manager.add(local.region);
        assertMatchesScan(manager, List.of(huge, local), -1, 64, -1);
        assertMatchesScan(manager, List.of(huge, local), 20000000, 64, -20000000);
        manager.remove(huge.region);
        assertMatchesScan(manager, List.of(local), 20000000, 64, -20000000);
    }

    @Test
    public void replacementRemovalAndClearDoNotLeaveStaleRegions() {
        WorldFlatFileRegionManager manager = new WorldFlatFileRegionManager("test_world");
        Bounds first = region("same", -16, -1, -16, -1, 1);
        Bounds replacement = region("same", 32, 47, 32, 47, 1);
        Bounds huge = region("huge", -30000000, 30000000, -30000000, 30000000, -1);
        manager.add(first.region);
        manager.add(first.region);
        manager.add(replacement.region);
        assertMatchesScan(manager, List.of(replacement), -1, 64, -1);
        assertMatchesScan(manager, List.of(replacement), 32, 64, 32);
        manager.remove(replacement.region);
        assertMatchesScan(manager, List.of(), 32, 64, 32);
        manager.add(first.region);
        manager.add(huge.region);
        manager.clearRegions();
        assertMatchesScan(manager, List.of(), -1, 64, -1);
        assertMatchesScan(manager, List.of(), 20000000, 64, 20000000);
    }

    @Test
    public void pointLookupDoesNotExamineDistantIndexedRegions() {
        WorldFlatFileRegionManager manager = new WorldFlatFileRegionManager("test_world");
        Bounds nearby = region("nearby", -16, -1, -16, -1, 1);
        Bounds distant = region("distant", 1600, 1615, 1600, 1615, 2);
        manager.add(nearby.region);
        manager.add(distant.region);
        clearInvocations(distant.region);
        assertMatchesScan(manager, List.of(nearby), -1, 64, -1);
        verifyNoInteractions(distant.region);
    }
}
