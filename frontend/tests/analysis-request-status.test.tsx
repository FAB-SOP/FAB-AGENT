import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {MemoryRouter} from 'react-router-dom';
import {AnalysisRequestStatus,isPreviousAnalysis} from '../src/components/AnalysisRequestStatus';

function render(job:Record<string,unknown>,approval?:Record<string,unknown>){
 return renderToStaticMarkup(<MemoryRouter><AnalysisRequestStatus job={job} approval={approval}/></MemoryRouter>);
}
describe('analysis request feedback',()=>{
 it('keeps the submitted question and correct approval destination visible while awaiting approval',()=>{
  const html=render({kind:'DEEP',status:'WAITING_APPROVAL',question:'이전 Etch 영향도 확인해줘',waitingRef:{type:'APPROVAL',id:'apv-123'}},{rationale:'이전 공정 제거량 확인이 필요합니다.',payload:{question:'정비 이후 시즈닝 이력을 확인해 주세요.'}});
  expect(html).toContain('이전 Etch 영향도 확인해줘');
  expect(html).toContain('이전 공정 제거량 확인이 필요합니다.');
  expect(html).toContain('/approvals?approval=apv-123');
  expect(html).not.toContain('분석 완료');
 });
 it('shows loading feedback for a running question without suggesting approval',()=>{
  const html=render({kind:'DEEP',status:'RUNNING',question:'압력 저하 영향은?'});
  expect(html).toContain('답변을 준비');
  expect(html).toContain('loading-spinner');
  expect(html).toContain('압력 저하 영향은?');
  expect(html).not.toContain('제안 검토하기');
 });
 it('distinguishes old analysis from the current question and keeps report jobs separate',()=>{
  expect(isPreviousAnalysis({jobId:'new',kind:'DEEP'},{jobId:'basic'})).toBe(true);
  expect(isPreviousAnalysis({jobId:'new',kind:'DEEP'},{jobId:'new'})).toBe(false);
  expect(isPreviousAnalysis({jobId:'report',kind:'REPORT'},{jobId:'basic'})).toBe(false);
 });
 it('shows failure reason and does not keep a loading spinner after failure',()=>{
  const html=render({status:'FAILED',error:{message:'응답 시간이 초과되었습니다.'}});
  expect(html).toContain('응답 시간이 초과되었습니다.');
  expect(html).not.toContain('loading-spinner');
 });
});
