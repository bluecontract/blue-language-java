package blue.language.mapping.model;

import blue.language.model.TypeBlueId;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@TypeBlueId("X3-BlueId")
public class X3 extends X {
    public AtomicInteger atomicIntegerField;
    public AtomicLong atomicLongField;
    public ConcurrentHashMap<String, Integer> concurrentMapField;
}
