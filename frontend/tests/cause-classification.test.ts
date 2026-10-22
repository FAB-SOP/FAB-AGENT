import {describe,it,expect} from 'vitest';
import {
 causeCategories,changeCategory,describeClassification,emptyClassificationDraft,isClassificationComplete,
 orderEquipmentOptions,toClassificationRequest,toggleEquipmentParameter,
} from '../src/domain/causeClassification';

describe('report cause classification',()=>{
 it('offers four Ms and hold as the only choices',()=>{
  expect(causeCategories).toEqual(['MATERIAL','MAN','METHOD','MACHINE','HOLD']);
 });
 it('requires a category and, for machine, at least one equipment parameter',()=>{
  expect(isClassificationComplete(emptyClassificationDraft)).toBe(false);
  expect(isClassificationComplete({category:'HOLD',equipmentParameters:[]})).toBe(true);
  const machine=changeCategory(emptyClassificationDraft,'MACHINE');
  expect(isClassificationComplete(machine)).toBe(false);
  expect(isClassificationComplete(toggleEquipmentParameter(machine,'head_pressure'))).toBe(true);
 });
 it('drops equipment parameters when leaving machine and never sends them for other categories',()=>{
  const machine=toggleEquipmentParameter(changeCategory(emptyClassificationDraft,'MACHINE'),'head_pressure');
  expect(changeCategory(machine,'METHOD').equipmentParameters).toEqual([]);
  expect(toClassificationRequest({category:'MAN',equipmentParameters:['head_pressure']})).toEqual({category:'MAN',equipmentParameters:[]});
  expect(toClassificationRequest(machine)).toEqual({category:'MACHINE',equipmentParameters:['head_pressure']});
  expect(toggleEquipmentParameter(machine,'head_pressure').equipmentParameters).toEqual([]);
 });
 it('lists incident sensors first with the same names as the sensor time series',()=>{
  const options=orderEquipmentOptions([
   {sensorId:'head_pressure',unit:'psi'},{sensorId:'platen_speed',unit:'rpm'},{sensorId:'slurry_flow',unit:'mL/min'},
  ],['slurry_flow']);
  expect(options.map(option=>option.sensorId)).toEqual(['slurry_flow','head_pressure','platen_speed']);
  expect(options[0]).toMatchObject({label:'슬러리 유량',unit:'mL/min',related:true});
  expect(orderEquipmentOptions([{sensorId:'unknown_sensor'}])[0].label).toBe('unknown_sensor');
 });
 it('describes saved classifications and legacy reports without one',()=>{
  expect(describeClassification(null)).toBe('분류 없음');
  expect(describeClassification({category:'HOLD',equipmentParameters:[],classifiedBy:'u',classifiedAt:''})).toBe('보류 (원인 미확정)');
  expect(describeClassification({category:'MACHINE',equipmentParameters:['head_pressure'],classifiedBy:'u',classifiedAt:''}))
   .toBe('Machine (설비) — 헤드 압력');
 });
});
