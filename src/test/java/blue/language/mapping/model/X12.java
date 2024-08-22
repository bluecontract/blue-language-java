package blue.language.mapping.model;

import blue.language.model.TypeBlueId;

import java.util.Deque;
import java.util.Queue;

@TypeBlueId("X12-BlueId")
public class X12 extends X1 {
    public Queue<String> stringQueueField;
    public Deque<Integer> integerDequeField;
}