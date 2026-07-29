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
    void shouldReturnCachedNodeOnCacheHit() {
        // given
        Node node = new Node().name("Test1");
        String blueId = BlueIdCalculator.calculateBlueId(node);
        List<Node> nodes = Arrays.asList(node);
        when(mockDelegate.fetchByBlueId(blueId)).thenReturn(nodes);

        // when
        List<Node> result1 = cachingProvider.fetchByBlueId(blueId);
        List<Node> result2 = cachingProvider.fetchByBlueId(blueId);

        // then
        assertEquals(nodes, result1);
        assertEquals(nodes, result2);
        verify(mockDelegate, times(1)).fetchByBlueId(blueId);
    }

    @Test
    void shouldDelegateOnCacheMiss() {
        // given
        Node node = new Node().name("Test2");
        String blueId = BlueIdCalculator.calculateBlueId(node);
        when(mockDelegate.fetchByBlueId(blueId)).thenReturn(null);

        // when
        List<Node> result = cachingProvider.fetchByBlueId(blueId);
        // then
        assertNull(result);
        verify(mockDelegate, times(1)).fetchByBlueId(blueId);
    }

    @Test
    void shouldEvictEntryAtCacheCapacity() {
        // Create nodes that will exceed the cache size
        // given
        Node largeNode1 = new Node().name("Large1").value(createRepeatedString('A', 300));
        Node largeNode2 = new Node().name("Large2").value(createRepeatedString('B', 300));
        String blueId1 = BlueIdCalculator.calculateBlueId(largeNode1);
        String blueId2 = BlueIdCalculator.calculateBlueId(largeNode2);

        when(mockDelegate.fetchByBlueId(blueId1)).thenReturn(Arrays.asList(largeNode1));
        when(mockDelegate.fetchByBlueId(blueId2)).thenReturn(Arrays.asList(largeNode2));

        // when
        cachingProvider.fetchByBlueId(blueId1);
        long sizeAfterFirst = cachingProvider.getCurrentSize();
        int cacheCountAfterFirst = cachingProvider.getCacheSize();
        cachingProvider.fetchByBlueId(blueId2);
        long sizeAfterSecond = cachingProvider.getCurrentSize();
        int cacheCountAfterSecond = cachingProvider.getCacheSize();

        // then
        assertTrue(sizeAfterFirst <= MAX_SIZE_BYTES);
        assertEquals(1, cacheCountAfterFirst);
        assertTrue(sizeAfterSecond <= MAX_SIZE_BYTES, "Cache size exceeds the maximum allowed size");
        assertEquals(1, cacheCountAfterSecond, "Expected only one item in the cache after eviction");
    }

    @Test
    void shouldCacheBasicNodeProviderResults() {
        // given
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

        // when
        List<Node> result1 = cachingBasicProvider.fetchByBlueId(dictBlueId);
        List<Node> result2 = cachingBasicProvider.fetchByBlueId(dictBlueId);
        long currentSize = cachingBasicProvider.getCurrentSize();
        int cacheSize = cachingBasicProvider.getCacheSize();

        // then
        assertNotNull(result1);
        assertEquals(1, result1.size());
        assertEquals("DictOfAToB", result1.get(0).getName());
        assertNotNull(result2);
        assertEquals(result1, result2);
        assertTrue(currentSize > 0);
        assertTrue(cacheSize > 0);
    }

    @Test
    void shouldRespectConfiguredCacheSize() {
        // given
        Node smallNode1 = new Node().name("Small1").value("Small content 1");
        Node smallNode2 = new Node().name("Small2").value("Small content 2");
        Node smallNode3 = new Node().name("Small3").value("Small content 3");

        String blueId1 = BlueIdCalculator.calculateBlueId(smallNode1);
        String blueId2 = BlueIdCalculator.calculateBlueId(smallNode2);
        String blueId3 = BlueIdCalculator.calculateBlueId(smallNode3);

        when(mockDelegate.fetchByBlueId(blueId1)).thenReturn(Arrays.asList(smallNode1));
        when(mockDelegate.fetchByBlueId(blueId2)).thenReturn(Arrays.asList(smallNode2));
        when(mockDelegate.fetchByBlueId(blueId3)).thenReturn(Arrays.asList(smallNode3));

        // when
        cachingProvider.fetchByBlueId(blueId1);
        cachingProvider.fetchByBlueId(blueId2);
        cachingProvider.fetchByBlueId(blueId3);
        long currentSize = cachingProvider.getCurrentSize();
        int cacheSize = cachingProvider.getCacheSize();

        // then
        assertTrue(currentSize <= MAX_SIZE_BYTES);
        assertTrue(cacheSize > 0);
        assertTrue(cacheSize <= 3);
    }

    private String createRepeatedString(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
