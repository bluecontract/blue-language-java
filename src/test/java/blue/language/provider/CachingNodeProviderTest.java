package blue.language.provider;

import blue.language.model.Node;
import blue.language.NodeProvider;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CachingNodeProviderTest {

    private NodeProvider mockDelegate;
    private CachingNodeProvider cachingProvider;
    private static final long MAX_SIZE_BYTES = 500;

    @BeforeEach
    void setUp() {
        mockDelegate = mock(NodeProvider.class);
        cachingProvider = new CachingNodeProvider(mockDelegate, MAX_SIZE_BYTES);
    }

    @Test
    void testCacheHit() {
        Node node = new Node().name("Test1");
        String blueId = BlueIdCalculator.calculateBlueId(node);
        List<Node> nodes = Arrays.asList(node);
        when(mockDelegate.fetchByBlueId(blueId)).thenReturn(nodes);

        // First call should hit the delegate
        List<Node> result1 = cachingProvider.fetchByBlueId(blueId);
        assertEquals(nodes, result1);
        verify(mockDelegate, times(1)).fetchByBlueId(blueId);

        // Second call should hit the cache
        List<Node> result2 = cachingProvider.fetchByBlueId(blueId);
        assertEquals(nodes, result2);
        verify(mockDelegate, times(1)).fetchByBlueId(blueId);
    }

    @Test
    void testCacheMiss() {
        Node node = new Node().name("Test2");
        String blueId = BlueIdCalculator.calculateBlueId(node);
        when(mockDelegate.fetchByBlueId(blueId)).thenReturn(null);

        List<Node> result = cachingProvider.fetchByBlueId(blueId);
        assertNull(result);
        verify(mockDelegate, times(1)).fetchByBlueId(blueId);
    }

    @Test
    void testCacheEviction() {
        // Create nodes that will exceed the cache size
        Node largeNode1 = new Node().name("Large1").value(createRepeatedString('A', 300));
        Node largeNode2 = new Node().name("Large2").value(createRepeatedString('B', 300));
        String blueId1 = BlueIdCalculator.calculateBlueId(largeNode1);
        String blueId2 = BlueIdCalculator.calculateBlueId(largeNode2);

        when(mockDelegate.fetchByBlueId(blueId1)).thenReturn(Arrays.asList(largeNode1));
        when(mockDelegate.fetchByBlueId(blueId2)).thenReturn(Arrays.asList(largeNode2));

        cachingProvider.fetchByBlueId(blueId1);
        long sizeAfterFirst = cachingProvider.getCurrentSize();
        int cacheCountAfterFirst = cachingProvider.getCacheSize();

        cachingProvider.fetchByBlueId(blueId2);
        long sizeAfterSecond = cachingProvider.getCurrentSize();
        int cacheCountAfterSecond = cachingProvider.getCacheSize();

        // Check if the cache size is within the limit
        assertTrue(sizeAfterSecond <= MAX_SIZE_BYTES, "Cache size exceeds the maximum allowed size");

        // Check if exactly one item was evicted
        assertEquals(1, cacheCountAfterSecond, "Expected only one item in the cache after eviction");
    }

    @Test
    void testWithBasicNodeProvider() {
        BasicNodeProvider basicProvider = new BasicNodeProvider();
        CachingNodeProvider cachingBasicProvider = new CachingNodeProvider(basicProvider, 10000);

        String a = "name: A";
        basicProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + basicProvider.getBlueIdByName("A");
        basicProvider.addSingleDocs(b);

        String dictOfAToB = "name: DictOfAToB\n" +
                            "type: Dictionary\n" +
                            "keyType: Text\n" +
                            "valueType: \n" +
                            "  blueId: " + basicProvider.getBlueIdByName("A") + "\n" +
                            "key1:\n" +
                            "  type:\n" +
                            "    blueId: " + basicProvider.getBlueIdByName("A") + "\n" +
                            "key2:\n" +
                            "  type:\n" +
                            "    blueId: " + basicProvider.getBlueIdByName("B");
        basicProvider.addSingleDocs(dictOfAToB);

        String dictBlueId = basicProvider.getBlueIdByName("DictOfAToB");

        // First call should hit the delegate
        List<Node> result1 = cachingBasicProvider.fetchByBlueId(dictBlueId);
        assertNotNull(result1);
        assertEquals(1, result1.size());
        assertEquals("DictOfAToB", result1.get(0).getName());

        // Second call should hit the cache
        List<Node> result2 = cachingBasicProvider.fetchByBlueId(dictBlueId);
        assertNotNull(result2);
        assertEquals(result1, result2);

        assertTrue(cachingBasicProvider.getCurrentSize() > 0);
        assertTrue(cachingBasicProvider.getCacheSize() > 0);
    }

    @Test
    void testCacheSize() {
        Node smallNode1 = new Node().name("Small1").value("Small content 1");
        Node smallNode2 = new Node().name("Small2").value("Small content 2");
        Node smallNode3 = new Node().name("Small3").value("Small content 3");

        String blueId1 = BlueIdCalculator.calculateBlueId(smallNode1);
        String blueId2 = BlueIdCalculator.calculateBlueId(smallNode2);
        String blueId3 = BlueIdCalculator.calculateBlueId(smallNode3);

        when(mockDelegate.fetchByBlueId(blueId1)).thenReturn(Arrays.asList(smallNode1));
        when(mockDelegate.fetchByBlueId(blueId2)).thenReturn(Arrays.asList(smallNode2));
        when(mockDelegate.fetchByBlueId(blueId3)).thenReturn(Arrays.asList(smallNode3));

        cachingProvider.fetchByBlueId(blueId1);
        cachingProvider.fetchByBlueId(blueId2);
        cachingProvider.fetchByBlueId(blueId3);

        assertTrue(cachingProvider.getCurrentSize() <= MAX_SIZE_BYTES);
        assertTrue(cachingProvider.getCacheSize() > 0);
        assertTrue(cachingProvider.getCacheSize() <= 3);
    }

    private String createRepeatedString(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}