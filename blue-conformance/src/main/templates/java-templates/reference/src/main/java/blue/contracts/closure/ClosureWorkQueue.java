package blue.contracts.closure;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
public final class ClosureWorkQueue {
    private final Deque<WorkOccurrence> queue=new ArrayDeque<WorkOccurrence>(); private final Set<String> identities=new HashSet<String>();
    public void add(WorkOccurrence work){if(!identities.add(work.workIdentity()))throw new IllegalArgumentException("duplicate work identity");queue.addLast(work);} public WorkOccurrence remove(){return queue.removeFirst();} public boolean isEmpty(){return queue.isEmpty();} public int size(){return queue.size();}
}
