"""Independent official-SDK test; invoked by optional Android JVM integration test.
No DJI credentials are printed or required. All credentials belong to the test session.
"""
import sys, json, ssl, io, zipfile, hashlib, struct
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from pathlib import Path
import boto3
from botocore.config import Config

base, code, certificate_path = sys.argv[1:]
context = ssl.create_default_context(cafile=certificate_path)
token = ''
def api(path, value=None):
    headers = {'X-Auth-Token': token}
    body = None
    if value is not None:
        headers['Content-Type'] = 'application/json'
        body = json.dumps(value).encode()
    with urlopen(Request(base+path,data=body,headers=headers),context=context,timeout=10) as response:
        return json.load(response)

token = api('/cloud/login', {'code':code})['data']['token']
bootstrap = api('/cloud/bootstrap')['data']; workspace = bootstrap['workspaceId']
listing = api(f'/wayline/api/v1/workspaces/{workspace}/waylines')['data']['list']
assert len(listing)==1 and listing[0]['drone_model_key']=='0-100-1'
request=Request(base+f'/wayline/api/v1/workspaces/{workspace}/waylines/{workspace}/url',headers={'X-Auth-Token':token})
with urlopen(request,context=context,timeout=10) as response:
    archive=zipfile.ZipFile(io.BytesIO(response.read()))
    assert set(archive.namelist())=={'wpmz/template.kml','wpmz/waylines.wpml'}
tag=bootstrap['route']['photoTag']
storage=api(f'/storage/api/v1/workspaces/{workspace}/sts', {})['data']
c=storage['credentials']
client=boto3.client('s3',endpoint_url=storage['endpoint'],region_name=storage['region'],
    aws_access_key_id=c['access_key_id'],aws_secret_access_key=c['access_key_secret'],aws_session_token=c['security_token'],verify=certificate_path,
    config=Config(signature_version='s3v4',s3={'addressing_style':'path','payload_signing_enabled':True},request_checksum_calculation='when_required',response_checksum_validation='when_required',retries={'max_attempts':0}))
prefix=storage['object_key_prefix']; bucket=storage['bucket']
client.head_bucket(Bucket=bucket)
assert client.get_bucket_location(Bucket=bucket)['LocationConstraint']=='us-east-1'
def jpeg_with_camera(source, payload):
    xmp=('http://ns.adobe.com/xap/1.0/\0<rdf:Description drone-dji:ImageSource="'+source+'"/>').encode()
    return b'\xff\xd8\xff\xe1'+struct.pack('>H',len(xmp)+2)+xmp+b'\xff\xda'+payload
one=jpeg_with_camera('InfraredCamera',b'original-exif-and-radiometric-bytes'*200)
key=prefix+'DJI_'+tag+'_IR.JPG'
client.put_object(Bucket=bucket,Key=key,Body=one,ContentType='image/jpeg')
assert client.get_object(Bucket=bucket,Key=key)['Body'].read()==one
assert client.head_object(Bucket=bucket,Key=key)['ContentLength']==len(one)
receipt={'result':0,'ext':{'is_original':True,'file_group_id':'flight-group'},'name':'DJI_'+tag+'_IR.JPG','object_key':key,'fingerprint':hashlib.md5(one).hexdigest()}
# A completion report may race the last per-file callback. It cannot make an incomplete batch ready.
assert api(f'/media/api/v1/workspaces/{workspace}/group-upload-callback',{'file_group_id':'flight-group','file_count':2,'file_uploaded_count':2})['code']==0
modern_receipt={k:v for k,v in receipt.items() if k!='result'}
modern_reply=api(f'/media/api/v1/workspaces/{workspace}/upload-callback',modern_receipt)
assert modern_reply['code']==0 and modern_reply['data']==key
assert api(f'/media/api/v1/workspaces/{workspace}/upload-callback',receipt)['code']==0
assert api('/cloud/status')['data']['photoCount']==1
assert api(f'/media/api/v1/workspaces/{workspace}/fast-upload',{'fingerprint':receipt['fingerprint']})['code']==0

two=jpeg_with_camera('InfraredCamera',b'multipart-original-bytes'*(300000))
second=prefix+'DJI_'+tag+'_IR_002.JPG'
upload=client.create_multipart_upload(Bucket=bucket,Key=second)['UploadId']
parts=[]
for number,start in enumerate(range(0,len(two),5*1024*1024),1):
    result=client.upload_part(Bucket=bucket,Key=second,UploadId=upload,PartNumber=number,Body=two[start:start+5*1024*1024])
    parts.append({'PartNumber':number,'ETag':result['ETag']})
assert len(client.list_parts(Bucket=bucket,Key=second,UploadId=upload)['Parts'])==len(parts)
client.complete_multipart_upload(Bucket=bucket,Key=second,UploadId=upload,MultipartUpload={'Parts':parts})
assert client.get_object(Bucket=bucket,Key=second)['Body'].read()==two
receipt.update(name='DJI_'+tag+'_IR_002.JPG',object_key=second,fingerprint=hashlib.md5(two).hexdigest())
assert api(f'/media/api/v1/workspaces/{workspace}/upload-callback',receipt)['code']==0
assert api('/cloud/status')['data']['photoCount']==2

other=prefix+'OLD_FLIGHT.JPG'
client.put_object(Bucket=bucket,Key=other,Body=one)
receipt.update(name='OLD_FLIGHT.JPG',object_key=other)
assert api(f'/media/api/v1/workspaces/{workspace}/upload-callback',receipt)['code']==0
assert api('/cloud/status')['data']['photoCount']==2 and api('/cloud/status')['data']['unmatchedCount']==1
wrong_camera=prefix+'DJI_'+tag+'_W.JPG'
client.put_object(Bucket=bucket,Key=wrong_camera,Body=jpeg_with_camera('WideCamera',b'wide-original-byte-content'))
receipt.update(name='DJI_'+tag+'_W.JPG',object_key=wrong_camera)
assert api(f'/media/api/v1/workspaces/{workspace}/upload-callback',receipt)['code']==0
status=api('/cloud/status')['data']
assert status['photoCount']==2 and status['unmatchedCount']==2 and status['storedUploadCount']==4
# A flight may return multiple cameras. Completion waits for all tagged originals,
# while only the selected camera enters the reconstruction inventory.
assert api(f'/media/api/v1/workspaces/{workspace}/group-upload-callback',{'file_group_id':'flight-group','file_count':3,'file_uploaded_count':3})['code']==0
assert 'All 3 returned originals stored; 2 selected-camera' in api('/cloud/status')['data']['status']
failed_receipt={**receipt,'result':17}
assert api(f'/media/api/v1/workspaces/{workspace}/upload-callback',failed_receipt)['code']!=0
status=api('/cloud/status')['data']
assert status['transfer']['callbackFailures']==1
diagnostics=json.dumps(status['transfer'])
assert all(private not in diagnostics for private in [token,workspace,c['access_key_id'],c['access_key_secret'],c['security_token']])
assert 'X-Auth-Token' not in diagnostics
try:
    client.put_object(Bucket=bucket,Key='other-mission/escape.JPG',Body=one)
    raise AssertionError('Unscoped storage key accepted')
except client.exceptions.ClientError: pass
try:
    client.put_object(Bucket=bucket,Key=key,Body=b'\xff\xd8changed')
    raise AssertionError('Immutable original overwritten')
except client.exceptions.ClientError: pass
print('Official SDK: route download, signed S3 PUT/GET/HEAD, multipart, byte preservation, modern/legacy callbacks, deduplication, out-of-order completion, camera/flight quarantine, and safe transfer status passed.')
