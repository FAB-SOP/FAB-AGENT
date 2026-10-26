import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {AnalysisResultBody} from '../src/components/InvestigationConversation';

const cause=(extra:Record<string,unknown>)=>({priority:1,hypothesis:'헤드 압력 부족',supportingEvidenceIds:['E-1'],missingData:[],nextCheck:'압력 센서 점검',...extra});
const render=(causes:Record<string,unknown>[])=>renderToStaticMarkup(<AnalysisResultBody result={{answer:'답',candidateCauses:causes,evidenceRefs:[{evidenceId:'E-1'},{evidenceId:'E-2'}]}}/>);

describe('cause counter evidence',()=>{
 it('says counter evidence was not found when the cause has none',()=>{
  const html=render([cause({conflictingEvidenceIds:[]})]);
  expect(html).toContain('반대 근거');
  expect(html).toContain('확보되지 않음');
 });
 it('lists counter evidence instead of the empty note when there is some',()=>{
  const html=render([cause({conflictingEvidenceIds:['E-2']})]);
  expect(html).toContain('반대·추가 확인 근거');
  expect(html).not.toContain('확보되지 않음');
 });
});
