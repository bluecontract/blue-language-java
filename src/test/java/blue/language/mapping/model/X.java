package blue.language.mapping.model;

import blue.language.model.TypeBlueId;

import java.math.BigDecimal;
import java.math.BigInteger;

@TypeBlueId("X-BlueId")
public class X {

    public enum TestEnum {
        SOME_ENUM_VALUE, ANOTHER_ENUM_VALUE
    }

    public String type;
    public byte byteField;
    public Byte byteObjectField;
    public short shortField;
    public Short shortObjectField;
    public int intField;
    public Integer integerField;
    public long longField;
    public Long longObjectField;
    public float floatField;
    public Float floatObjectField;
    public double doubleField;
    public Double doubleObjectField;
    public boolean booleanField;
    public Boolean booleanObjectField;
    public char charField;
    public Character characterField;
    public String stringField;
    public BigInteger bigIntegerField;
    public BigDecimal bigDecimalField;
    public TestEnum enumField;
}
