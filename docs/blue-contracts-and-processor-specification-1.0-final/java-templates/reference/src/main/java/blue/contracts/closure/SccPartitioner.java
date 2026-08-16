package blue.contracts.closure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic Tarjan partition; component members are sorted by DocumentId. */
public final class SccPartitioner {
    private int next; private final Map<DocumentId,Integer> index=new HashMap<DocumentId,Integer>(); private final Map<DocumentId,Integer> low=new HashMap<DocumentId,Integer>(); private final Deque<DocumentId> stack=new ArrayDeque<DocumentId>(); private final Set<DocumentId> onStack=new HashSet<DocumentId>(); private final List<List<DocumentId>> components=new ArrayList<List<DocumentId>>();
    public List<List<DocumentId>> partition(Map<DocumentId,List<DocumentId>> graph){next=0;index.clear();low.clear();stack.clear();onStack.clear();components.clear();List<DocumentId> nodes=new ArrayList<DocumentId>(graph.keySet());Collections.sort(nodes);for(DocumentId node:nodes)if(!index.containsKey(node))visit(node,graph);for(List<DocumentId> c:components)Collections.sort(c);Collections.sort(components,new java.util.Comparator<List<DocumentId>>(){public int compare(List<DocumentId>a,List<DocumentId>b){return a.get(0).compareTo(b.get(0));}});List<List<DocumentId>> result=new ArrayList<List<DocumentId>>();for(List<DocumentId> component:components)result.add(Collections.unmodifiableList(new ArrayList<DocumentId>(component)));return Collections.unmodifiableList(result);}
    private void visit(DocumentId v,Map<DocumentId,List<DocumentId>> graph){index.put(v,next);low.put(v,next);next++;stack.push(v);onStack.add(v);List<DocumentId> targets=new ArrayList<DocumentId>(graph.containsKey(v)?graph.get(v):Collections.<DocumentId>emptyList());Collections.sort(targets);for(DocumentId w:targets){if(!index.containsKey(w)){visit(w,graph);low.put(v,Math.min(low.get(v),low.get(w)));}else if(onStack.contains(w)){low.put(v,Math.min(low.get(v),index.get(w)));}}if(low.get(v).equals(index.get(v))){List<DocumentId> c=new ArrayList<DocumentId>();DocumentId w;do{w=stack.pop();onStack.remove(w);c.add(w);}while(!w.equals(v));components.add(c);}}
}
