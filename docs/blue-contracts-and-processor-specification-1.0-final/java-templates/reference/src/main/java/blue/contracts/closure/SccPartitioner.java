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

/**
 * Deterministic Tarjan partition in canonical target-before-source order.
 * Component members are sorted by DocumentId. Among currently incomparable
 * sink components, minimum member DocumentId is the first normative tie-break;
 * it is unique because managed DocumentIds are unique and components disjoint.
 */
public final class SccPartitioner {
    private int next; private final Map<DocumentId,Integer> index=new HashMap<DocumentId,Integer>(); private final Map<DocumentId,Integer> low=new HashMap<DocumentId,Integer>(); private final Deque<DocumentId> stack=new ArrayDeque<DocumentId>(); private final Set<DocumentId> onStack=new HashSet<DocumentId>(); private final List<List<DocumentId>> components=new ArrayList<List<DocumentId>>();
    public List<List<DocumentId>> partition(Map<DocumentId,List<DocumentId>> graph){
        next=0;index.clear();low.clear();stack.clear();onStack.clear();components.clear();
        List<DocumentId> nodes=new ArrayList<DocumentId>(graph.keySet());
        Collections.sort(nodes);
        for(DocumentId node:nodes)if(!index.containsKey(node))visit(node,graph);
        for(List<DocumentId> component:components)Collections.sort(component);

        Map<DocumentId,Integer> owner=new HashMap<DocumentId,Integer>();
        for(int componentIndex=0;componentIndex<components.size();componentIndex++){
            for(DocumentId member:components.get(componentIndex)){
                owner.put(member,Integer.valueOf(componentIndex));
            }
        }
        List<Set<Integer>> outgoing=new ArrayList<Set<Integer>>();
        for(int componentIndex=0;componentIndex<components.size();componentIndex++){
            outgoing.add(new HashSet<Integer>());
        }
        for(Map.Entry<DocumentId,List<DocumentId>> entry:graph.entrySet()){
            Integer source=owner.get(entry.getKey());
            for(DocumentId targetDocumentId:entry.getValue()){
                Integer target=owner.get(targetDocumentId);
                if(source!=null&&target!=null&&!source.equals(target)){
                    outgoing.get(source.intValue()).add(target);
                }
            }
        }

        Set<Integer> remaining=new HashSet<Integer>();
        for(int componentIndex=0;componentIndex<components.size();componentIndex++){
            remaining.add(Integer.valueOf(componentIndex));
        }
        List<List<DocumentId>> result=new ArrayList<List<DocumentId>>();
        while(!remaining.isEmpty()){
            Integer selected=null;
            for(Integer candidate:remaining){
                boolean sink=true;
                for(Integer target:outgoing.get(candidate.intValue())){
                    if(remaining.contains(target)){sink=false;break;}
                }
                if(sink&&(selected==null||components.get(candidate.intValue()).get(0)
                        .compareTo(components.get(selected.intValue()).get(0))<0)){
                    selected=candidate;
                }
            }
            if(selected==null)throw new IllegalStateException("SCC condensation cycle");
            result.add(Collections.unmodifiableList(new ArrayList<DocumentId>(
                    components.get(selected.intValue()))));
            remaining.remove(selected);
        }
        return Collections.unmodifiableList(result);
    }
    private void visit(DocumentId v,Map<DocumentId,List<DocumentId>> graph){index.put(v,next);low.put(v,next);next++;stack.push(v);onStack.add(v);List<DocumentId> targets=new ArrayList<DocumentId>(graph.containsKey(v)?graph.get(v):Collections.<DocumentId>emptyList());Collections.sort(targets);for(DocumentId w:targets){if(!index.containsKey(w)){visit(w,graph);low.put(v,Math.min(low.get(v),low.get(w)));}else if(onStack.contains(w)){low.put(v,Math.min(low.get(v),index.get(w)));}}if(low.get(v).equals(index.get(v))){List<DocumentId> c=new ArrayList<DocumentId>();DocumentId w;do{w=stack.pop();onStack.remove(w);c.add(w);}while(!w.equals(v));components.add(c);}}
}
