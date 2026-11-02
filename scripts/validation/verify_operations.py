#!/usr/bin/env python3
"""Verify isolated Compose persistence, recovery and structured observability on real PostgreSQL."""
import argparse
from datetime import datetime,timedelta
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.error import HTTPError,URLError
from urllib.request import Request,urlopen
from uuid import uuid4

ROOT=Path(__file__).resolve().parents[2]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'target/m6-operations-report.json')
    args=parser.parse_args()
    project='scheduler-m6-'+uuid4().hex[:12]
    secret='verification-'+uuid4().hex
    env=dict(os.environ,COMPOSE_FILE=str(ROOT/'compose.yaml'),COMPOSE_PROJECT_NAME=project,SCHEDULER_IMAGE=project+'-app:verification',APP_PORT='0',POSTGRES_PORT='0',POSTGRES_USER='verification',POSTGRES_DB='scheduler_operations',POSTGRES_PASSWORD=secret)
    command=['docker','compose','--profile','app']
    report={'project':project,'checks':[],'samples':[]}
    started=False
    base=None
    def run(arguments,**kwargs):
        return subprocess.run(arguments,env=env,cwd=ROOT,check=True,text=True,**kwargs)
    def compose(*arguments):
        return run(command+list(arguments),capture_output=True)
    def refresh_base():
        nonlocal base
        address=compose('port','app','8080').stdout.strip()
        base='http://127.0.0.1:'+address.rsplit(':',1)[1]
        report['baseUrl']=base
        if base not in report.setdefault('baseUrls',[]):report['baseUrls'].append(base)
    def check(name):
        report['checks'].append(name);print('PASS: '+name,flush=True)
    def sql(statement):
        result=run(command+['exec','-T','postgres','sh','-c','exec psql -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],input=statement,capture_output=True)
        return result.stdout.strip()
    def call(path,expected=None,method='GET',body=None,request_id=None,timeout=12):
        request_id=request_id or 'operations-'+uuid4().hex
        headers={'Accept':'application/json','X-Request-ID':request_id}
        data=None
        if body is not None:data=json.dumps(body).encode();headers['Content-Type']='application/json'
        request=Request(base+path,headers=headers,data=data,method=method)
        try:response=urlopen(request,timeout=timeout)
        except HTTPError as error:response=error
        with response:
            parsed=json.loads(response.read());status=response.status;received=dict((k.lower(),v) for k,v in response.headers.items())
        if expected is not None:assert status==expected,(path,expected,status,parsed)
        assert received.get('x-request-id')==request_id,(path,'correlation header missing')
        if status>=400 and not path.startswith('/actuator/'):
            assert parsed['status']==status and parsed['requestId']==request_id
            assert parsed['code']=='TEMPORARILY_UNAVAILABLE' if status==503 else True
            assert secret not in json.dumps(parsed)
        report['samples'].append({'method':method,'path':path,'status':status,'body':parsed,'headers':received})
        return parsed,received
    def ready(timeout=60):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            try:
                body,_=call('/actuator/health/readiness',timeout=3)
                if body.get('status')=='UP':return
            except (OSError,ValueError,AssertionError):pass
            time.sleep(.2)
        raise RuntimeError('Application readiness did not recover within deadline')
    def metric(name,tags=None):
        from urllib.parse import urlencode
        result,_=call('/actuator/metrics/'+name,200)
        if tags:
            result,_=call('/actuator/metrics/'+name+'?'+urlencode([('tag',k+':'+v) for k,v in tags.items()]),200)
        return result
    def count_success():
        available,_=call('/actuator/metrics',200)
        if 'scheduler.booking.requests' not in available['names']:return 0
        unfiltered=metric('scheduler.booking.requests')
        outcomes=next((t['values'] for t in unfiltered.get('availableTags',[]) if t['tag']=='outcome'),[])
        if 'success' not in outcomes:return 0
        result=metric('scheduler.booking.requests',{'outcome':'success','reason':'none'})
        return next(m['value'] for m in result['measurements'] if m['statistic']=='COUNT')
    try:
        existing=run(['docker','ps','-aq','--filter','label=com.docker.compose.project='+project],capture_output=True).stdout.strip()
        assert not existing,'Refusing to reuse an existing Compose project'
        started=True
        compose('up','--build','-d','--wait')
        refresh_base()
        ready();check('Application ready with freshly migrated real PostgreSQL')
        runtime_java=compose('exec','-T','app','java','-version')
        runtime_uid=compose('exec','-T','app','id','-u').stdout.strip()
        assert runtime_uid=='10001','Application container is not running as the expected non-root user'
        runtime_container=compose('ps','-q','app').stdout.strip()
        runtime_inspection=json.loads(run(['docker','inspect',runtime_container],capture_output=True).stdout)[0]
        report['environment']={'javaVersion':(runtime_java.stderr+runtime_java.stdout).strip(),
                               'runtimeUid':runtime_uid,
                               'dockerServerVersion':run(['docker','version','--format','{{.Server.Version}}'],capture_output=True).stdout.strip(),
                               'applicationImageId':runtime_inspection['Image']}
        check('Packaged application runs as UID 10001; runtime Java, Docker and image identity recorded')
        run(['./scripts/demo/seed.sh'],capture_output=True)
        spec=importlib.util.spec_from_file_location('scheduler_demo',ROOT/'scripts/demo/client.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        demo=module.Demo(base+'/api/v1');demo.run()
        report['demoSamples']=demo.samples
        created=next(s['body'] for s in demo.samples if s['method']=='POST' and s['status']==201)
        identifier=created['id'];appointment_path='/api/v1/appointments/'+identifier
        assert sql('SELECT count(*) FROM appointments;')=='1'
        check('Dynamic demo commits exactly one appointment')
        compose('restart','app');refresh_base();report['baseUrlAfterRestart']=base;ready()
        restored,_=call(appointment_path,200);assert restored==created
        assert sql('SELECT count(*) FROM appointments;')=='1'
        check('Appointment survives application restart with the same database volume')
        app_id=compose('ps','-q','app').stdout.strip()
        app_state=json.loads(run(['docker','inspect',app_id],capture_output=True).stdout)[0]
        success_before_outage=count_success()
        compose('stop','postgres')
        live,_=call('/actuator/health/liveness',200);assert live['status']=='UP'
        down,_=call('/actuator/health/readiness',503);assert down['status']=='DOWN'
        call(appointment_path,503)
        start=datetime.fromisoformat(created['startsAt'].replace('Z','+00:00'))+timedelta(hours=1)
        request={k:created[k] for k in ('customerId','vehicleId','dealershipId','serviceTypeId')};request['startsAt']=start.isoformat(timespec='seconds')
        unavailable,outage_headers=call('/api/v1/appointments',503,method='POST',body=request)
        check('Database outage keeps liveness UP, readiness DOWN and reads/writes controlled 503')
        compose('start','postgres');refresh_base();ready()
        assert compose('ps','-q','app').stdout.strip()==app_id,'Application was unexpectedly replaced'
        recovered_state=json.loads(run(['docker','inspect',app_id],capture_output=True).stdout)[0]
        assert recovered_state['State']['StartedAt']==app_state['State']['StartedAt'],'Application restarted during database recovery'
        assert recovered_state['RestartCount']==app_state['RestartCount'],'Application auto-restarted during database outage'
        assert count_success()==success_before_outage,'Failed outage POST published success'
        restored,_=call(appointment_path,200);assert restored==created
        assert sql('SELECT count(*) FROM appointments;')=='1'
        check('Database recovers without application restart; original appointment remains and failed POST created nothing')
        # Meters are lazy and process-local; the application was restarted above.
        success_before=count_success()
        marker='PRIVATE_DATABASE_DIAGNOSTIC'
        sql("CREATE FUNCTION operations_failure_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION '"+marker+" DEMO0000000000001 Alex Morgan'; END $$; CREATE CONSTRAINT TRIGGER operations_failure AFTER INSERT ON appointments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION operations_failure_fn();")
        failed,failed_headers=call('/api/v1/appointments',500,method='POST',body=request)
        assert marker not in json.dumps(failed)
        assert sql('SELECT count(*) FROM appointments;')=='1'
        sql('DROP TRIGGER operations_failure ON appointments; DROP FUNCTION operations_failure_fn();')
        second,success_headers=call('/api/v1/appointments',201,method='POST',body=request)
        assert sql('SELECT count(*) FROM appointments;')=='2'
        assert count_success()==success_before+1
        check('Deferred commit failure rolls back; subsequent explicit booking succeeds and success count increments only once')
        # Outcome/reason and lock resource labels are bounded, never entity identifiers.
        for name in ('scheduler.booking.requests','scheduler.booking.duration','scheduler.booking.lock.wait'):
            data=metric(name)
            allowed={'outcome','reason','resource'}
            for tag in data.get('availableTags',[]):
                assert tag['tag'] in allowed,(name,tag)
                for value in tag['values']:
                    assert value not in (created['id'],created['customerId'],created['vehicleId'])
        metric('hikaricp.connections.max')
        check('Booking outcome, duration, lock-wait and pool metrics are exposed with bounded labels')
        logs=compose('logs','--no-color','--no-log-prefix','app').stdout
        records=[]
        for line in logs.splitlines():
            if line.startswith('{'):records.append(json.loads(line))
        events=[r for r in records if r.get('event')=='booking_outcome']
        assert events,'No structured booking outcome events found'
        assert len([r for r in events if r.get('outcome')=='success'])==2
        failure_id=failed_headers['x-request-id'];success_id=success_headers['x-request-id']
        for request_id,outcome in [(outage_headers['x-request-id'],'error'),(failure_id,'error'),(success_id,'success')]:
            matching=[r for r in events if r.get('requestId')==request_id]
            assert len(matching)==1 and matching[0]['outcome']==outcome,(request_id,matching)
            assert 'elapsedMs' in matching[0] and 'reason' in matching[0]
            if outcome!='success':assert 'appointmentId' not in matching[0]
            assert any(r.get('event')=='request_complete' and r.get('requestId')==request_id for r in records)
        for sensitive in (secret,marker,'DEMO0000000000001','DEMO0000000000002','DEMO0000000000003','Alex Morgan','Jordan Lee'):
            assert sensitive not in logs,'Sensitive content leaked in application logs'
        report['structuredBookingEvents']=events
        report['structuredLogRecords']=len(records)
        check('Structured logs correlate requests, omit sensitive database/customer data and record no false success')
        report['status']='passed'
    except BaseException as failure:
        report['status']='failed';report['error']=type(failure).__name__+': '+str(failure)
        raise
    finally:
        cleanup_error=None
        if started:
            try:
                log_result=compose('logs','--no-color','--no-log-prefix','app')
                log_path=args.output.with_suffix('.app.log');log_path.parent.mkdir(parents=True,exist_ok=True);log_path.write_text(log_result.stdout)
                report['applicationLog']=str(log_path)
            except Exception as error:
                report['logCaptureError']=type(error).__name__+': '+str(error)
            try:
                compose('down','--volumes');report['cleanup']='Dedicated project containers and volume removed; image retained as build cache'
            except Exception as error:
                cleanup_error=error;report['cleanup']='Failed: '+str(error);report['status']='failed'
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2)+'\n')
        if cleanup_error:raise RuntimeError('Dedicated test-project cleanup failed; inspect report') from cleanup_error
    print('Operational verification passed. Report: '+str(args.output))


if __name__=='__main__':main()
