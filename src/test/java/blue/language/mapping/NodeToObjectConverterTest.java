package blue.language.mapping;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.mapping.model.*;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.Properties;
import blue.language.utils.TypeClassResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.*;

public class NodeToObjectConverterTest {

    private Blue blue;
    private NodeToObjectConverter converter;

    @BeforeEach
    void setUp() {
        blue = new Blue();
        converter = new NodeToObjectConverter(new TypeClassResolver("blue.language.mapping.model"));
    }

    @Test
    public void testXConversion() throws Exception {
        String xYaml = "type:\n" +
                       "  blueId: X-BlueId\n" +
                       "byteField: 127\n" +
                       "byteObjectField: -128\n" +
                       "shortField: 32767\n" +
                       "shortObjectField: -32768\n" +
                       "intField: 2147483647\n" +
                       "integerField: -2147483648\n" +
                       "longField: 9223372036854775807\n" +
                       "longObjectField: -9223372036854775808\n" +
                       "floatField: 3.14\n" +
                       "floatObjectField: -3.14\n" +
                       "doubleField: 3.141592653589793\n" +
                       "doubleObjectField: -3.141592653589793\n" +
                       "booleanField: true\n" +
                       "booleanObjectField: false\n" +
                       "charField: A\n" +
                       "characterField: Z\n" +
                       "stringField: Hello, World!\n" +
                       "bigIntegerField:\n" +
                       "  type:\n" +
                       "    blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                       "  value: \"123456789012345678901234567890\"\n" +
                       "bigDecimalField:\n" +
                       "  type:\n" +
                       "    blueId: " + Properties.DOUBLE_TYPE_BLUE_ID + "\n" +
                       "  value: \"3.14159265358979323846\"\n" +
                       "enumField: SOME_ENUM_VALUE";

        Node xNode = blue.yamlToNode(xYaml);
        X x = converter.convert(xNode, X.class);

        assertNotNull(x);
        assertEquals((byte) 127, x.byteField);
        assertEquals(Byte.valueOf((byte) -128), x.byteObjectField);
        assertEquals((short) 32767, x.shortField);
        assertEquals(Short.valueOf((short) -32768), x.shortObjectField);
        assertEquals(2147483647, x.intField);
        assertEquals(Integer.valueOf(-2147483648), x.integerField);
        assertEquals(9007199254740991L, x.longField);
        assertEquals(Long.valueOf(-9007199254740991L), x.longObjectField);
        assertEquals(3.14f, x.floatField, 0.0001);
        assertEquals(-3.14f, x.floatObjectField, 0.0001);
        assertEquals(3.141592653589793, x.doubleField, 0.0000000000000001);
        assertEquals(-3.141592653589793, x.doubleObjectField, 0.0000000000000001);
        assertTrue(x.booleanField);
        assertFalse(x.booleanObjectField);
        assertEquals('A', x.charField);
        assertEquals(Character.valueOf('Z'), x.characterField);
        assertEquals("Hello, World!", x.stringField);
        assertEquals(new BigInteger("123456789012345678901234567890"), x.bigIntegerField);
        assertEquals(new BigDecimal("3.141592653589793"), x.bigDecimalField);
        assertEquals(X.TestEnum.SOME_ENUM_VALUE, x.enumField);
    }

    @Test
    public void testX1Conversion() throws Exception {
        String x1Yaml = "type:\n" +
                        "  blueId: X1-BlueId\n" +
                        "name: X1 Instance\n" +
                        "intField: 42\n" +
                        "stringField: X1 String\n" +
                        "intArrayField:\n" +
                        "  type:\n" +
                        "    blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                        "  itemType:\n" +
                        "    blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                        "  items: [1, 2, 3, 4, 5]\n" +
                        "stringListField:\n" +
                        "  type:\n" +
                        "    blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                        "  itemType:\n" +
                        "    blueId: " + Properties.TEXT_TYPE_BLUE_ID + "\n" +
                        "  items:\n" +
                        "    - apple\n" +
                        "    - banana\n" +
                        "    - cherry\n" +
                        "integerSetField:\n" +
                        "  type:\n" +
                        "    blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                        "  itemType:\n" +
                        "    blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                        "  items: [10, 20, 30, 40, 50]";

        Node x1Node = blue.yamlToNode(x1Yaml);
        X1 x1 = converter.convert(x1Node, X1.class);

        assertNotNull(x1);
        assertEquals(42, x1.intField);
        assertEquals("X1 String", x1.stringField);
        assertArrayEquals(new int[]{1, 2, 3, 4, 5}, x1.intArrayField);
        assertEquals(Arrays.asList("apple", "banana", "cherry"), x1.stringListField);
        assertEquals(new HashSet<>(Arrays.asList(10, 20, 30, 40, 50)), x1.integerSetField);
    }

    @Test
    public void testX2Conversion() throws Exception {
        String x2Yaml = "name: X2 Instance\n" +
                        "type:\n" +
                        "  blueId: X2-BlueId\n" +
                        "doubleField: 3.14159\n" +
                        "booleanField: true\n" +
                        "stringIntMapField:\n" +
                        "  key1: 100\n" +
                        "  key2: 200\n" +
                        "  key3: 300";

        Node x2Node = blue.yamlToNode(x2Yaml);
        X2 x2 = converter.convert(x2Node, X2.class);

        assertNotNull(x2);
        assertEquals(3.14159, x2.doubleField, 0.00001);
        assertTrue(x2.booleanField);
        assertEquals(3, x2.stringIntMapField.size());
        assertEquals(100, x2.stringIntMapField.get("key1"));
        assertEquals(200, x2.stringIntMapField.get("key2"));
        assertEquals(300, x2.stringIntMapField.get("key3"));
    }

    @Test
    public void testX3Conversion() throws Exception {
        String x3Yaml = "name: X3 Instance\n" +
                        "type:\n" +
                        "  blueId: X3-BlueId\n" +
                        "longField: 1234567890\n" +
                        "atomicIntegerField: 42\n" +
                        "atomicLongField: 9876543210\n" +
                        "concurrentMapField:\n" +
                        "  key1: 111\n" +
                        "  key2: 222\n" +
                        "  key3: 333";

        Node x3Node = blue.yamlToNode(x3Yaml);
        X3 x3 = converter.convert(x3Node, X3.class);

        assertNotNull(x3);
        assertEquals(1234567890L, x3.longField);
        assertEquals(42, x3.atomicIntegerField.get());
        assertEquals(9876543210L, x3.atomicLongField.get());
        assertEquals(3, x3.concurrentMapField.size());
        assertEquals(111, x3.concurrentMapField.get("key1"));
        assertEquals(222, x3.concurrentMapField.get("key2"));
        assertEquals(333, x3.concurrentMapField.get("key3"));
    }

    @Test
    public void testX11Conversion() throws Exception {
        String x11Yaml = "name: X11 Instance\n" +
                         "type:\n" +
                         "  blueId: X11-BlueId\n" +
                         "intField: 11\n" +
                         "stringField: X11 String\n" +
                         "intArrayField: [11, 22, 33]\n" +
                         "stringListField: [red, green, blue]\n" +
                         "integerSetField: [111, 222, 333]\n" +
                         "nestedListField:\n" +
                         "  - [a, b, c]\n" +
                         "  - [d, e, f]\n" +
                         "complexMapField:\n" +
                         "  key1: [1, 2, 3]\n" +
                         "  key2: [4, 5, 6]";

        Node x11Node = blue.yamlToNode(x11Yaml);
        X11 x11 = converter.convert(x11Node, X11.class);

        assertNotNull(x11);
        assertEquals(11, x11.intField);
        assertEquals("X11 String", x11.stringField);
        assertArrayEquals(new int[]{11, 22, 33}, x11.intArrayField);
        assertEquals(Arrays.asList("red", "green", "blue"), x11.stringListField);
        assertEquals(new HashSet<>(Arrays.asList(111, 222, 333)), x11.integerSetField);

        assertEquals(2, x11.nestedListField.size());
        assertEquals(Arrays.asList("a", "b", "c"), x11.nestedListField.get(0));
        assertEquals(Arrays.asList("d", "e", "f"), x11.nestedListField.get(1));

        assertEquals(2, x11.complexMapField.size());
        assertEquals(Arrays.asList(1, 2, 3), x11.complexMapField.get("key1"));
        assertEquals(Arrays.asList(4, 5, 6), x11.complexMapField.get("key2"));
    }

    @Test
    public void testX12Conversion() throws Exception {
        String xVariationsYaml =
                "name: X Variations\n" +
                "type:\n" +
                "  blueId: X12-BlueId\n" +
                "byteField: 100\n" +
                "integerField: 1000\n" +
                "stringField: Base X field\n" +
                "intArrayField: [1, 2, 3, 4, 5]\n" +
                "stringListField: [apple, banana, cherry]\n" +
                "integerSetField: [10, 20, 30, 40, 50]\n" +
                "stringQueueField: [first, second, third]\n" +
                "integerDequeField: [1000, 2000, 3000]\n";

        Node xVariationsNode = blue.yamlToNode(xVariationsYaml);
        X12 x12 = converter.convert(xVariationsNode, X12.class);

        assertNotNull(x12);

        assertEquals(100, x12.byteField);
        assertEquals(Integer.valueOf(1000), x12.integerField);
        assertEquals("Base X field", x12.stringField);

        assertArrayEquals(new int[]{1, 2, 3, 4, 5}, x12.intArrayField);
        assertEquals(Arrays.asList("apple", "banana", "cherry"), x12.stringListField);
        assertEquals(new HashSet<>(Arrays.asList(10, 20, 30, 40, 50)), x12.integerSetField);

        assertEquals(Arrays.asList("first", "second", "third"), new ArrayList<>(x12.stringQueueField));

        Deque<Integer> expectedDeque = new ArrayDeque<>(Arrays.asList(1000, 2000, 3000));
        assertIterableEquals(expectedDeque, x12.integerDequeField);
    }

    @Test
    public void testYConversion() throws Exception {
        String yYaml = "name: Y Instance\n" +
                       "type:\n" +
                       "  blueId: Y-BlueId\n" +
                       "xField:\n" +
                       "  type:\n" +
                       "    blueId: X-BlueId\n" +
                       "  intField: 100\n" +
                       "  stringField: X in Y\n" +
                       "x1Field:\n" +
                       "  type:\n" +
                       "    blueId: X1-BlueId\n" +
                       "  intArrayField: [1, 2, 3]\n" +
                       "  stringListField: [a, b, c]\n" +
                       "x2Field:\n" +
                       "  type:\n" +
                       "    blueId: X2-BlueId\n" +
                       "  stringIntMapField:\n" +
                       "    key1: 10\n" +
                       "    key2: 20\n" +
                       "xListField:\n" +
                       "  - type:\n" +
                       "      blueId: X-BlueId\n" +
                       "    intField: 1\n" +
                       "  - type:\n" +
                       "      blueId: X-BlueId\n" +
                       "    intField: 2\n" +
                       "xMapField:\n" +
                       "  key1:\n" +
                       "    type:\n" +
                       "      blueId: X-BlueId\n" +
                       "    intField: 10\n" +
                       "  key2:\n" +
                       "    type:\n" +
                       "      blueId: X-BlueId\n" +
                       "    intField: 20\n" +
                       "x1SetField:\n" +
                       "  - type:\n" +
                       "      blueId: X1-BlueId\n" +
                       "    intArrayField: [4, 5, 6]\n" +
                       "  - type:\n" +
                       "      blueId: X1-BlueId\n" +
                       "    intArrayField: [7, 8, 9]\n" +
                       "x2MapField:\n" +
                       "  mapKey1:\n" +
                       "    type:\n" +
                       "      blueId: X2-BlueId\n" +
                       "    stringIntMapField:\n" +
                       "      innerKey1: 30\n" +
                       "      innerKey2: 40\n" +
                       "  mapKey2:\n" +
                       "    type:\n" +
                       "      blueId: X2-BlueId\n" +
                       "    stringIntMapField:\n" +
                       "      innerKey3: 50\n" +
                       "      innerKey4: 60\n" +
                       "xArrayField:\n" +
                       "  - type:\n" +
                       "      blueId: X-BlueId\n" +
                       "    intField: 100\n" +
                       "  - type:\n" +
                       "      blueId: X-BlueId\n" +
                       "    intField: 200\n" +
                       "wildcardXListField:\n" +
                       "  - type:\n" +
                       "      blueId: X1-BlueId\n" +
                       "    intArrayField: [10, 11, 12]\n" +
                       "  - type:\n" +
                       "      blueId: X2-BlueId\n" +
                       "    stringIntMapField:\n" +
                       "      wildcardKey: 70";

        Node yNode = blue.yamlToNode(yYaml);
        Y y = converter.convert(yNode, Y.class);

        assertNotNull(y);
        assertNotNull(y.xField);
        assertEquals(100, y.xField.intField);
        assertEquals("X in Y", y.xField.stringField);

        assertNotNull(y.x1Field);
        assertArrayEquals(new int[]{1, 2, 3}, y.x1Field.intArrayField);
        assertEquals(Arrays.asList("a", "b", "c"), y.x1Field.stringListField);

        assertNotNull(y.x2Field);
        assertEquals(10, y.x2Field.stringIntMapField.get("key1"));
        assertEquals(20, y.x2Field.stringIntMapField.get("key2"));

        assertNotNull(y.xListField);
        assertEquals(2, y.xListField.size());
        assertEquals(1, y.xListField.get(0).intField);
        assertEquals(2, y.xListField.get(1).intField);

        assertNotNull(y.xMapField);
        assertEquals(2, y.xMapField.size());
        assertEquals(10, y.xMapField.get("key1").intField);
        assertEquals(20, y.xMapField.get("key2").intField);

        assertNotNull(y.x1SetField);
        assertEquals(2, y.x1SetField.size());
        assertTrue(y.x1SetField.stream().anyMatch(x1 -> Arrays.equals(x1.intArrayField, new int[]{4, 5, 6})));
        assertTrue(y.x1SetField.stream().anyMatch(x1 -> Arrays.equals(x1.intArrayField, new int[]{7, 8, 9})));

        assertNotNull(y.x2MapField);
        assertEquals(2, y.x2MapField.size());
        assertEquals(30, y.x2MapField.get("mapKey1").stringIntMapField.get("innerKey1"));
        assertEquals(40, y.x2MapField.get("mapKey1").stringIntMapField.get("innerKey2"));
        assertEquals(50, y.x2MapField.get("mapKey2").stringIntMapField.get("innerKey3"));
        assertEquals(60, y.x2MapField.get("mapKey2").stringIntMapField.get("innerKey4"));

        assertNotNull(y.xArrayField);
        assertEquals(2, y.xArrayField.length);
        assertEquals(100, y.xArrayField[0].intField);
        assertEquals(200, y.xArrayField[1].intField);

        assertNotNull(y.wildcardXListField);
        assertEquals(2, y.wildcardXListField.size());
        assertTrue(y.wildcardXListField.get(0) instanceof X1);
        assertTrue(y.wildcardXListField.get(1) instanceof X2);
        assertArrayEquals(new int[]{10, 11, 12}, ((X1) y.wildcardXListField.get(0)).intArrayField);
        assertEquals(70, ((X2) y.wildcardXListField.get(1)).stringIntMapField.get("wildcardKey"));
    }

    @Test
    public void testY1Conversion() throws Exception {
        String y1Yaml = "name: Y1 Instance\n" +
                        "type:\n" +
                        "  blueId: Y1-BlueId\n" +
                        "xField:\n" +
                        "  type:\n" +
                        "    blueId: X-BlueId\n" +
                        "  intField: 100\n" +
                        "x11Field:\n" +
                        "  type:\n" +
                        "    blueId: X11-BlueId\n" +
                        "  nestedListField:\n" +
                        "    - [a, b, c]\n" +
                        "    - [d, e, f]\n" +
                        "x12Field:\n" +
                        "  type:\n" +
                        "    blueId: X12-BlueId\n" +
                        "  stringQueueField: [first, second, third]\n" +
                        "x11ListField:\n" +
                        "  - type:\n" +
                        "      blueId: X11-BlueId\n" +
                        "    complexMapField:\n" +
                        "      key1: [1, 2, 3]\n" +
                        "  - type:\n" +
                        "      blueId: X11-BlueId\n" +
                        "    complexMapField:\n" +
                        "      key2: [4, 5, 6]";

        Node y1Node = blue.yamlToNode(y1Yaml);
        Y1 y1 = converter.convert(y1Node, Y1.class);

        assertNotNull(y1);
        assertEquals(100, y1.xField.intField);
        assertEquals(2, y1.x11Field.nestedListField.size());
        assertEquals(Arrays.asList("a", "b", "c"), y1.x11Field.nestedListField.get(0));
        assertEquals(Arrays.asList("d", "e", "f"), y1.x11Field.nestedListField.get(1));
        assertEquals(Arrays.asList("first", "second", "third"), new ArrayList<>(y1.x12Field.stringQueueField));
        assertEquals(2, y1.x11ListField.size());
        assertEquals(Arrays.asList(1, 2, 3), y1.x11ListField.get(0).complexMapField.get("key1"));
        assertEquals(Arrays.asList(4, 5, 6), y1.x11ListField.get(1).complexMapField.get("key2"));
    }

    @Test
    public void testObjectVariants() throws Exception {
        String personTestDataYaml = "name: Person Testing\n" +
                                    "type:\n" +
                                    "  blueId: PersonTestData-BlueId\n" +
                                    "alice1:\n" +
                                    "  name: Alice\n" +
                                    "  surname: Smith\n" +
                                    "  age: 25\n" +
                                    "alice2:\n" +
                                    "  name: Alice\n" +
                                    "  surname: Smith\n" +
                                    "  age: 25\n" +
                                    "alice3:\n" +
                                    "  name: Alice\n" +
                                    "  surname: Smith\n" +
                                    "  age: 25\n" +
                                    "alice4:\n" +
                                    "  name: Alice\n" +
                                    "  surname: Smith\n" +
                                    "  age: 25\n" +
                                    "alice5:\n" +
                                    "  type:\n" +
                                    "    blueId: Nurse-BlueId\n" +
                                    "  name: Alice\n" +
                                    "  surname: Smith\n" +
                                    "  age: 25\n" +
                                    "  yearsOfExperience: 4";

        Node node = blue.yamlToNode(personTestDataYaml);

        PersonObjectExample data = converter.convert(node, PersonObjectExample.class);

        assertNotNull(data);

        assertNotNull(data.alice1);
        assertTrue(data.alice1.matches(BlueIdCalculator.calculateBlueId(data.alice2)));

        assertNotNull(data.alice2);
        assertEquals("Alice", data.alice2.getName());
        assertEquals("Smith", data.alice2.getProperties().get("surname").getValue());
        assertEquals(BigInteger.valueOf(25), data.alice2.getProperties().get("age").getValue());

        assertNotNull(data.alice3);
        assertEquals("Alice", data.alice3.get("name"));
        assertEquals("Smith", data.alice3.get("surname"));
        assertEquals(BigInteger.valueOf(25), data.alice3.get("age"));

        assertNotNull(data.alice4);
        assertEquals("Alice", data.alice4.getName());
        assertEquals("Smith", data.alice4.getSurname());
        assertEquals(Integer.valueOf(25), data.alice4.getAge());

        assertNotNull(data.alice5);
        assertInstanceOf(Nurse.class, data.alice5);
        Nurse nurse = (Nurse) data.alice5;
        assertEquals("Alice", nurse.getName());
        assertEquals("Smith", nurse.getSurname());
        assertEquals(Integer.valueOf(25), nurse.getAge());
        assertEquals(Integer.valueOf(4), nurse.yearsOfExperience);
    }

    @Test
    public void testValueVariants() throws Exception {
        String personTestDataYaml = "type:\n" +
                                    "  blueId: PersonValue-BlueId\n" +
                                    "age1:\n" +
                                    "  type:\n" +
                                    "    blueId: DHmxTkFbXePZHCHCYmQr2dSzcNLcryFVjXVHkdQrrZr8\n" +
                                    "  name: Official Age\n" +
                                    "  description: Description for official age\n" +
                                    "  value: 25\n" +
                                    "age2:\n" +
                                    "  type:\n" +
                                    "    blueId: DHmxTkFbXePZHCHCYmQr2dSzcNLcryFVjXVHkdQrrZr8\n" +
                                    "  name: Official Age\n" +
                                    "  description: Description for official age\n" +
                                    "  value: 25\n" +
                                    "age3:\n" +
                                    "  type:\n" +
                                    "    blueId: DHmxTkFbXePZHCHCYmQr2dSzcNLcryFVjXVHkdQrrZr8\n" +
                                    "  name: Official Age\n" +
                                    "  description: Description for official age\n" +
                                    "  value: 25";

        Node node = blue.yamlToNode(personTestDataYaml);

        PersonValueExample data = converter.convert(node, PersonValueExample.class);

        assertNotNull(data);

        assertEquals(Integer.valueOf(25), data.age1);

        assertEquals("Official Age", data.age2Name);
        assertEquals("Description for official age", data.age2Description);
        assertEquals(Integer.valueOf(25), data.age2);

        assertNotNull(data.age3);
        assertEquals("Official Age", data.age3.getName());
        assertEquals("Description for official age", data.age3.getDescription());
        assertEquals(INTEGER_TYPE_BLUE_ID, data.age3.getType().getBlueId());
        assertEquals(BigInteger.valueOf(25), data.age3.getValue());

        assertEquals("PersonValue-BlueId", node.getType().getBlueId());
    }

    @Test
    public void testListVariants() throws Exception {
        String personTestDataYaml = "type:\n" +
                                    "  blueId: PersonList-BlueId\n" +
                                    "team1:\n" +
                                    "  - type:\n" +
                                    "      blueId: Doctor-BlueId\n" +
                                    "    name: Adam\n" +
                                    "    specialization: surgeon\n" +
                                    "  - type:\n" +
                                    "      blueId: Nurse-BlueId\n" +
                                    "    name: Betty\n" +
                                    "    yearsOfExperience: 12\n" +
                                    "team2:\n" +
                                    "  name: Team2 Name\n" +
                                    "  description: Team2 Description\n" +
                                    "  items:\n" +
                                    "    - type:\n" +
                                    "        blueId: Doctor-BlueId\n" +
                                    "      name: Adam\n" +
                                    "      specialization: surgeon\n" +
                                    "    - type:\n" +
                                    "        blueId: Nurse-BlueId\n" +
                                    "      name: Betty\n" +
                                    "      yearsOfExperience: 12\n" +
                                    "team3:\n" +
                                    "  - type:\n" +
                                    "      blueId: Doctor-BlueId\n" +
                                    "    name: Adam\n" +
                                    "    specialization: surgeon\n" +
                                    "  - type:\n" +
                                    "      blueId: Nurse-BlueId\n" +
                                    "    name: Betty\n" +
                                    "    yearsOfExperience: 12";

        Node node = blue.yamlToNode(personTestDataYaml);

        PersonListExample data = converter.convert(node, PersonListExample.class);

        assertNotNull(data);

        assertNotNull(data.team1);
        assertEquals(2, data.team1.size());
        assertInstanceOf(Doctor.class, data.team1.get(0));
        assertInstanceOf(Nurse.class, data.team1.get(1));
        Doctor doctor1 = (Doctor) data.team1.get(0);
        Nurse nurse1 = (Nurse) data.team1.get(1);
        assertEquals("Adam", doctor1.getName());
        assertEquals("surgeon", doctor1.getSpecialization());
        assertEquals("Betty", nurse1.getName());
        assertEquals(Integer.valueOf(12), nurse1.yearsOfExperience);

        assertEquals("Team2 Name", data.team2Name);
        assertEquals("Team2 Description", data.team2Description);
        assertNotNull(data.team2);
        assertEquals(2, data.team2.size());
        assertInstanceOf(Doctor.class, data.team2.get(0));
        assertInstanceOf(Nurse.class, data.team2.get(1));
        Doctor doctor2 = (Doctor) data.team2.get(0);
        Nurse nurse2 = (Nurse) data.team2.get(1);
        assertEquals("Adam", doctor2.getName());
        assertEquals("surgeon", doctor2.getSpecialization());
        assertEquals("Betty", nurse2.getName());
        assertEquals(Integer.valueOf(12), nurse2.yearsOfExperience);

        assertNotNull(data.team3);
        assertEquals(2, data.team3.getItems().size());
        Node doctorNode = data.team3.getItems().get(0);
        Node nurseNode = data.team3.getItems().get(1);

        assertEquals("Adam", doctorNode.getName());
        assertEquals("Doctor-BlueId", doctorNode.getType().getBlueId());
        assertEquals("surgeon", doctorNode.getProperties().get("specialization").getValue());

        assertEquals("Betty", nurseNode.getName());
        assertEquals("Nurse-BlueId", nurseNode.getType().getBlueId());
        assertEquals(BigInteger.valueOf(12), nurseNode.getProperties().get("yearsOfExperience").getValue());

        assertEquals("PersonList-BlueId", node.getType().getBlueId());
    }

    @Test
    public void testDictionaryVariants() throws Exception {
        String personTestDataYaml = "team1:\n" +
                                    "  person1:\n" +
                                    "    type:\n" +
                                    "      blueId: Doctor-BlueId\n" +
                                    "    name: Adam\n" +
                                    "    specialization: surgeon\n" +
                                    "  person2:\n" +
                                    "    type:\n" +
                                    "      blueId: Nurse-BlueId\n" +
                                    "    name: Betty\n" +
                                    "    yearsOfExperience: 12\n" +
                                    "team2:\n" +
                                    "  person1:\n" +
                                    "    type:\n" +
                                    "      blueId: Doctor-BlueId\n" +
                                    "    name: Adam\n" +
                                    "    specialization: surgeon\n" +
                                    "  person2:\n" +
                                    "    type:\n" +
                                    "      blueId: Nurse-BlueId\n" +
                                    "    name: Betty\n" +
                                    "    yearsOfExperience: 12\n" +
                                    "team3:\n" +
                                    "  1:\n" +
                                    "    type:\n" +
                                    "      blueId: Doctor-BlueId\n" +
                                    "    name: Adam\n" +
                                    "    specialization: surgeon\n" +
                                    "  2:\n" +
                                    "    type:\n" +
                                    "      blueId: Nurse-BlueId\n" +
                                    "    name: Betty\n" +
                                    "    yearsOfExperience: 12";

        Node node = blue.yamlToNode(personTestDataYaml);

        PersonDictionaryExample data = converter.convert(node, PersonDictionaryExample.class);

        assertNotNull(data);

        assertNotNull(data.team1);
        assertEquals(2, data.team1.size());
        assertInstanceOf(Doctor.class, data.team1.get("person1"));
        assertInstanceOf(Nurse.class, data.team1.get("person2"));
        Doctor doctor1 = (Doctor) data.team1.get("person1");
        Nurse nurse1 = (Nurse) data.team1.get("person2");
        assertEquals("Adam", doctor1.getName());
        assertEquals("surgeon", doctor1.getSpecialization());
        assertEquals("Betty", nurse1.getName());
        assertEquals(Integer.valueOf(12), nurse1.yearsOfExperience);

        assertNotNull(data.team2);
        assertEquals(2, data.team2.size());
        assertInstanceOf(Doctor.class, data.team2.get("person1"));
        assertInstanceOf(Nurse.class, data.team2.get("person2"));
        Doctor doctor2 = (Doctor) data.team2.get("person1");
        Nurse nurse2 = (Nurse) data.team2.get("person2");
        assertEquals("Adam", doctor2.getName());
        assertEquals("surgeon", doctor2.getSpecialization());
        assertEquals("Betty", nurse2.getName());
        assertEquals(Integer.valueOf(12), nurse2.yearsOfExperience);

        assertNotNull(data.team3);
        assertEquals(2, data.team3.size());
        assertInstanceOf(Doctor.class, data.team3.get(1));
        assertInstanceOf(Nurse.class, data.team3.get(2));
        Doctor doctor3 = (Doctor) data.team3.get(1);
        Nurse nurse3 = (Nurse) data.team3.get(2);
        assertEquals("Adam", doctor3.getName());
        assertEquals("surgeon", doctor3.getSpecialization());
        assertEquals("Betty", nurse3.getName());
        assertEquals(Integer.valueOf(12), nurse3.yearsOfExperience);

    }

    @Test
    public void testAbstractClassExtension() throws Exception {
        String z1Yaml = "type:\n" +
                        "  blueId: Z1-BlueId\n" +
                        "commonField: Common Value\n" +
                        "z1SpecificField: Z1 Specific Value";

        Node z1Node = blue.yamlToNode(z1Yaml);
        Z1 z1 = converter.convert(z1Node, Z1.class);

        assertNotNull(z1);
        assertEquals("Common Value", z1.commonField);
        assertEquals("Z1 Specific Value", z1.z1SpecificField);
        assertEquals("Z1 implementation", z1.getAbstractMethod());

        Z z = z1;
        assertEquals("Common Value", z.commonField);
        assertEquals("Z1 implementation", z.getAbstractMethod());
    }

    @Test
    public void testListOfAbstractClassExtensions() throws Exception {
        String zContainerYaml =
                "type:\n" +
                "  blueId: ZContainer-BlueId\n" +
                "containerName: My Z Container\n" +
                "zList:\n" +
                "  - type:\n" +
                "      blueId: Z1-BlueId\n" +
                "    commonField: Common Value 1\n" +
                "    z1SpecificField: Z1 Specific Value 1\n" +
                "  - type:\n" +
                "      blueId: Z1-BlueId\n" +
                "    commonField: Common Value 2\n" +
                "    z1SpecificField: Z1 Specific Value 2\n";

        Node zContainerNode = blue.yamlToNode(zContainerYaml);
        ZContainer zContainer = converter.convert(zContainerNode, ZContainer.class);

        assertNotNull(zContainer);
        assertEquals("My Z Container", zContainer.containerName);
        assertNotNull(zContainer.zList);
        assertEquals(2, zContainer.zList.size());

        Z firstZ = zContainer.zList.get(0);
        assertInstanceOf(Z1.class, firstZ);
        Z1 firstZ1 = (Z1) firstZ;
        assertEquals("Common Value 1", firstZ1.commonField);
        assertEquals("Z1 Specific Value 1", firstZ1.z1SpecificField);
        assertEquals("Z1 implementation", firstZ1.getAbstractMethod());

        Z secondZ = zContainer.zList.get(1);
        assertInstanceOf(Z1.class, secondZ);
        Z1 secondZ1 = (Z1) secondZ;
        assertEquals("Common Value 2", secondZ1.commonField);
        assertEquals("Z1 Specific Value 2", secondZ1.z1SpecificField);
        assertEquals("Z1 implementation", secondZ1.getAbstractMethod());
    }

    @Test
    public void testXSubscriptionConversion() throws Exception {
        String yaml = "type:\n" +
                      "  blueId: Y-BlueId\n" +
                      "subscriptions:\n" +
                      "  - type:\n" +
                      "      blueId: XSubscription-BlueId\n" +
                      "    subscriptionId: 1\n" +
                      "  - type:\n" +
                      "      blueId: XSubscription-BlueId\n" +
                      "    subscriptionId: 5";

        Node node = blue.yamlToNode(yaml);
        Y y = converter.convert(node, Y.class);

        assertNotNull(y);
        assertNotNull(y.subscriptions);
        assertEquals(2, y.subscriptions.size());

        XSubscription subscription1 = y.subscriptions.get(0);
        XSubscription subscription2 = y.subscriptions.get(1);

        assertEquals(Integer.valueOf(1), subscription1.getSubscriptionId());
        assertEquals(Integer.valueOf(5), subscription2.getSubscriptionId());
    }

}