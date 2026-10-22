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

import {questionEntries,QuestionHistory} from '../src/components/AnalysisRequestStatus';
it('retains every submitted question from the server timeline, including requests without a result',()=>{
 const entries=questionEntries([
  {type:'QUESTION_ASKED',ref:{type:'JOB',id:'job-1'},message:'추가 조사 질문: 압력 확인해줘',at:'2026-10-21T00:00:00Z'},
  {type:'OTHER',message:'기본 분석'},
  {type:'QUESTION_ASKED',ref:{type:'JOB',id:'job-2'},message:'추가 조사 질문: 이전 Etch 영향은?',at:'2026-10-21T00:01:00Z'},
 ]);
 const html=renderToStaticMarkup(<QuestionHistory entries={entries} jobs={[{status:'WAITING_APPROVAL'},{status:'SUCCEEDED'}]} results={[{jobId:'job-1',summary:'압력 저하가 관측되었습니다.'}]}/>);
 expect(html).toContain('이전 Etch 영향은?');expect(html).toContain('압력 확인해줘');expect(html).toContain('압력 저하가 관측되었습니다.');expect(entries[0].jobId).toBe('job-1');
});
it('pairs each question with its own resumed answer without collapsing the conversation',()=>{
 const threads=[{rootJob:{jobId:'q1',question:'같은 질문'},latestJob:{jobId:'r1',status:'SUCCEEDED'},jobs:[{jobId:'q1'},{jobId:'r1'}]},{rootJob:{jobId:'q2',question:'같은 질문'},latestJob:{jobId:'q2',status:'SUCCEEDED'},jobs:[{jobId:'q2'}]}];
 const html=renderToStaticMarkup(<QuestionHistory entries={[{jobId:'q1',question:'같은 질문'},{jobId:'q2',question:'같은 질문'}]} threads={threads} results={[{jobId:'r1',summary:'첫 번째 회신을 반영한 답변'},{jobId:'q2',summary:'두 번째 질문의 답변'}]}/>);
 const first=html.split('</article>')[0];
 expect(first).toContain('첫 번째 회신을 반영한 답변');expect(first).not.toContain('두 번째 질문의 답변');expect(html).not.toContain('<details');expect(html).toContain('AI 답변');
});
it('shows a pending AI proposal directly beneath the question',()=>{
 const html=renderToStaticMarkup(<MemoryRouter><QuestionHistory entries={[{jobId:'q1',question:'이전 Etch 영향은?'}]} jobs={[{jobId:'q1',status:'WAITING_APPROVAL',waitingRef:{type:'APPROVAL',id:'a1'}}]} proposal={{approvalId:'a1',rationale:'추가 확인이 필요합니다.',payload:{question:'정비 이력을 확인해 주세요.'}}}/></MemoryRouter>);
 expect(html).toContain('이전 Etch 영향은?');expect(html).toContain('추가 확인이 필요합니다.');expect(html).toContain('/approvals?approval=a1');
});
