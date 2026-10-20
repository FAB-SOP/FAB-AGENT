import {describe,it,expect} from 'vitest';
import {newlyReceived,incidentTarget} from '../src/api/incident-notifications';
import type {Incident} from '../src/api/types';
const a={incidentId:'a',lotId:'CURRENT-L006',equipmentId:'CMP-PM3'} as Incident;
const b={...a,incidentId:'b',lotId:'CURRENT-L009'};
describe('incoming incidents',()=>{
 it('does not turn initial historical incidents into new alerts',()=>expect(newlyReceived(null,[a,b])).toEqual([]));
 it('alerts only unseen IDs, including after an empty initial list',()=>{expect(newlyReceived(new Set(['a']),[a,b])).toEqual([b]);expect(newlyReceived(new Set(),[a])).toEqual([a])});
 it('does not alert again on status or version updates',()=>expect(newlyReceived(new Set(['a']),[{...a,version:10,status:'INVESTIGATING'}])).toEqual([]));
 it('targets the matching lot and equipment without redirecting automatically',()=>expect(incidentTarget(b)).toBe('/dashboard?lot=CURRENT-L009&pm=CMP-PM3'));
});
