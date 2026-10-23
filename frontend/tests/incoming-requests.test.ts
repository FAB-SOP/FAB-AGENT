import {describe,it,expect} from 'vitest';
import {incomingOnly} from '../src/components/WorkItems';

describe('my work incoming requests',()=>{
 it('lists accepted requests once, skipping ones already shown as my work',()=>{
  const data={items:[{taskId:'t1',requestId:'chk_1'}],incomingRequests:[{requestId:'chk_1'},{requestId:'chk_2'}]};
  expect(incomingOnly(data).map(request=>request.requestId)).toEqual(['chk_2']);
  expect(incomingOnly(undefined)).toEqual([]);
 });
});
