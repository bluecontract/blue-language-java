"""Exact shared graph evidence checks extracted unchanged from the verified RUN018 oracle."""

def graph_checks(aliases, ids, starts, weights, require, exact_equal, gas_check):
    def did(value):
        require(isinstance(value, dict) and set(value) == {'value'} and isinstance(value['value'], str), 'GRAPH_RAW_DOCUMENT_ID')
        return value['value']

    def scalar(value):
        return value['value'] if isinstance(value, dict) and 'value' in value else value

    def by_id(values, field='documentId'):
        result = {did(v[field]): v for v in values}
        require(len(result) == len(values), 'GRAPH_DUPLICATE_DOCUMENT_ROW')
        return result

    def receipt_prefix(before, after):
        require(set(before) == set(after) == set(aliases), 'GRAPH_RECORD_INVENTORY')
        for alias in aliases:
            old, new = before[alias], after[alias]
            require(new['documentId'] == ids[alias] and old['documentId'] == ids[alias], 'GRAPH_RECORD_OWNER')
            require(exact_equal(old['historyBasis'], new['historyBasis']), 'GRAPH_HISTORY_BASIS_CHANGED')
            require(exact_equal(old['receipts'], new['receipts'][:len(old['receipts'])]), 'GRAPH_RECEIPT_PREFIX')
            history = new['receipts']
            require(history and [r['epoch'] for r in history] == list(range(len(history))), 'GRAPH_RECEIPT_EPOCHS')
            require(new['epoch'] == history[-1]['epoch'], 'GRAPH_HOST_EPOCH')
            require(len({r['receiptIdentity'] for r in history}) == len(history), 'GRAPH_DUPLICATE_RECEIPT')
            require(sum(r['kind'] == 'INITIALIZATION' for r in history) == 1, 'GRAPH_INITIALIZATION_COUNT')
            require(exact_equal(history[0], starts[alias]['epoch0Receipt']), 'GRAPH_INITIAL_RECEIPT_CHANGED')
            require(all(did(r['documentId']) == ids[alias] for r in history), 'GRAPH_RECEIPT_OWNER')
            require(exact_equal(new['events'], [e for r in history for e in r['emittedEvents']]), 'GRAPH_RETAINED_EVENTS')

    def sccs(snapshot):
        documents = by_id(snapshot['managedDocuments'])
        edges = {d: set() for d in documents}
        for edge in snapshot['occurrences']:
            if edge['active']:
                a, b = did(edge['sourceDocumentId']), did(edge['targetDocumentId'])
                require(a in documents and b in documents, 'GRAPH_ACTIVE_EDGE_ENDPOINT')
                edges[a].add(b)
        def reach(start):
            seen, todo = set(), [start]
            while todo:
                item = todo.pop()
                if item in seen: continue
                seen.add(item); todo.extend(edges[item] - seen)
            return seen
        reachable = {d: reach(d) for d in documents}
        return [set(group) for group in sorted({tuple(sorted(e for e in documents if e in reachable[d] and d in reachable[e])) for d in documents})]

    def expand_member(value, master):
        if isinstance(value, list): return [expand_member(v, master) for v in value]
        if isinstance(value, dict):
            return {k: master + v[4:] if k == 'blueId' and isinstance(v, str) and v.startswith('this#')
                    else expand_member(v, master) for k, v in value.items()}
        return value

    def snapshot_check(snapshot):
        documents = by_id(snapshot['managedDocuments'])
        derived = {frozenset(s) for s in sccs(snapshot)}
        components = snapshot['components']
        require({frozenset(did(d) for d in c['orderedMemberDocumentIds']) for c in components} == derived
                and len(components) == len(derived), 'GRAPH_COMPONENT_SCC')
        for component in components:
            members = [did(d) for d in component['orderedMemberDocumentIds']]
            blueids = component['orderedMemberBlueIds']
            require(len(members) == len(blueids) and len(set(members)) == len(members), 'GRAPH_COMPONENT_MEMBERS')
            require(blueids == [documents[d]['blueId'] for d in members], 'GRAPH_COMPONENT_EXACT_IDS')
            if component['kind'] == 'CYCLIC':
                master = component['masterBlueId']
                proof = component['completeCyclicProof']['declaredPlaceholderSet']
                require(len(proof) == len(members) and component['cyclicProofIdentity'], 'GRAPH_COMPLETE_CYCLIC_PROOF')
                indices = []
                for owner, blueid in zip(members, blueids):
                    require(blueid.startswith(master + '#'), 'GRAPH_CYCLIC_MEMBER_ID')
                    index = int(blueid.rsplit('#', 1)[1]); indices.append(index)
                    require(0 <= index < len(proof), 'GRAPH_CYCLIC_MEMBER_INDEX')
                    require(exact_equal(expand_member(proof[index], master), documents[owner]['document']), 'GRAPH_CYCLIC_PROOF_BYTES')
                require(sorted(indices) == list(range(len(proof))), 'GRAPH_CYCLIC_MEMBER_BIJECTION')
            else:
                require(component['kind'] == 'ACYCLIC' and len(members) == 1
                        and component['completeCyclicProof'] is None, 'GRAPH_ACYCLIC_COMPONENT')
        return documents

    def trace_check(gas, full, budget):
        gas_check(gas, budget, weights)
        require(gas['rejected'] is None and len(full) == len(gas['charges']), 'GRAPH_GAS_TRACE_LENGTH')
        for raw, charge in zip(full, gas['charges']):
            normalized = {'sequence':raw['sequence'], 'label':raw['namespace'].lower()+'.'+raw['counter'],
                          'quantity':raw['quantity'], 'weight':raw['weight'], 'amount':raw['subtotal']}
            require(exact_equal(normalized, charge), 'GRAPH_GAS_TRACE_BINDING')

    return did, scalar, by_id, receipt_prefix, sccs, snapshot_check, trace_check
