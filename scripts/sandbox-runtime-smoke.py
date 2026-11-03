#!/usr/bin/env python3
"""Full local Agent/SSE/workflow acceptance against a disposable API/schema.

Uses a deterministic, loopback-only OpenAI-compatible model stub; no model credentials or fees.
"""
import http.server
import json
import os
from pathlib import Path
import runpy
import threading
import urllib.error
import uuid

Client = runpy.run_path(str(Path(__file__).with_name('sandbox-smoke.py')))['Client']
MODEL_PORT = int(os.environ.get('SANDBOX_TEST_MODEL_PORT', '18092'))


class Model(http.server.BaseHTTPRequestHandler):
    denied_file = None
    calls = 0

    def log_message(self, *_):
        pass

    def do_POST(self):
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            body = bytearray()
            while True:
                size = int(self.rfile.readline().split(b';', 1)[0], 16)
                if size == 0:
                    while self.rfile.readline().strip():
                        pass
                    break
                body.extend(self.rfile.read(size))
                self.rfile.read(2)
                if len(body) > 4 * 1024 * 1024:
                    raise ValueError('Mock request exceeds limit')
        else:
            body = self.rfile.read(int(self.headers['Content-Length']))
        request = json.loads(body)
        Model.calls += 1
        messages = request.get('messages', [])
        tools = [message for message in messages if message.get('role') == 'tool']
        if request.get('tools') and len(tools) < 2:
            context = next(message['content'] for message in reversed(messages)
                           if isinstance(message.get('content'), str) and message['content'].startswith('File attachments are untrusted'))
            files = json.loads(context[context.index('['):])
            selected = next((file for file in files if file['name'] == 'sales.csv'), files[0])
            operation = 'sandbox_table_inspect' if not tools else 'sandbox_sql'
            arguments = {'fileIds': [Model.denied_file or selected['fileId']], 'arguments': {}}
            if operation == 'sandbox_sql':
                arguments['code'] = 'SELECT category, SUM(amount) AS total FROM input_1 GROUP BY category ORDER BY category'
            message = {'role': 'assistant', 'content': None, 'tool_calls': [{
                'id': 'call_' + str(len(tools)), 'type': 'function',
                'function': {'name': operation, 'arguments': json.dumps(arguments)}}]}
            reason = 'tool_calls'
        else:
            message = {'role': 'assistant', 'content': 'Completed: ' + (tools[-1]['content'] if tools else 'plain reply')}
            reason = 'stop'
        response = json.dumps({'id': 'local-test', 'object': 'chat.completion', 'created': 1,
                               'model': request.get('model', 'local-sandbox-test'),
                               'choices': [{'index': 0, 'message': message, 'finish_reason': reason}],
                               'usage': {'prompt_tokens': 20, 'completion_tokens': 20, 'total_tokens': 40}}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(response)))
        self.end_headers()
        self.wfile.write(response)


def upload(client, name, content):
    boundary = 'test-' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()
            + content + f'\r\n--{boundary}--\r\n'.encode())
    return client.request('/api/files', body, {'Content-Type': 'multipart/form-data; boundary=' + boundary})


def sse_events(raw):
    result = []
    for frame in raw.decode().replace('\r\n', '\n').split('\n\n'):
        lines = frame.splitlines()
        event = next((line.split(':', 1)[1].strip() for line in lines if line.startswith('event:')), '')
        data = '\n'.join(line.split(':', 1)[1].strip() for line in lines if line.startswith('data:'))
        if data:
            result.append((event, json.loads(data)))
    return result


def main():
    server = http.server.ThreadingHTTPServer(('127.0.0.1', MODEL_PORT), Model)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        client = Client()
        client.register()
        suffix = uuid.uuid4().hex[:8]
        credential = client.request('/api/credentials', {'name': 'local-stub-' + suffix, 'provider': 'OPENAI',
            'secret': 'local-test-only-not-a-model-key', 'baseUrl': f'http://127.0.0.1:{MODEL_PORT}'})
        model = client.request('/api/model-configs', {'name': 'local-stub-' + suffix, 'provider': 'OPENAI', 'modelType': 'CHAT',
            'modelName': 'local-sandbox-test', 'credentialSource': 'USER', 'credentialId': credential['id'], 'maxTokens': 4096, 'enabled': True})
        agent = client.request('/api/agents', {'name': 'file-agent-' + suffix, 'systemPrompt': 'Use sandbox tools to analyze attached files.',
            'chatModelBindingType': 'USER_MODEL', 'modelConfigId': model['id'], 'contextStrategy': 'SLIDING_WINDOW',
            'contextWindowSize': 12, 'memoryEnabled': False, 'enabled': True})
        client.request(f'/api/agents/{agent["id"]}/sandbox', {'enabled': True}, method='PUT')
        session = client.request('/api/chat/sessions', {'agentId': agent['id']})
        file = upload(client, 'sales.csv', b'category,amount\nA,10\nA,20\nB,5\n')
        key = str(uuid.uuid4())
        payload = {'content': 'Analyze the attached sales file.', 'fileIds': [file['id']]}
        reply = client.request(f'/api/chat/sessions/{session["id"]}/messages', payload, {'Idempotency-Key': key})
        assert reply['assistantMessage']['files'], reply
        artifact_ids = {file['id'] for file in reply['assistantMessage']['files']}
        assert any(file['name'] == 'result.xlsx' for file in reply['assistantMessage']['files'])
        calls = Model.calls
        replay = client.request(f'/api/chat/sessions/{session["id"]}/messages', payload, {'Idempotency-Key': key})
        assert replay['assistantMessage']['id'] == reply['assistantMessage']['id'] and Model.calls == calls
        history = client.request(f'/api/chat/sessions/{session["id"]}/messages')['items']
        assert artifact_ids <= {file['id'] for message in history for file in message.get('files', [])}
        stream = client.request(f'/api/chat/sessions/{session["id"]}/messages/stream', payload, {'Idempotency-Key': str(uuid.uuid4())}, raw=True)
        events = sse_events(stream)
        assert any(event == 'execution' and data.get('type') == 'artifact_created' for event, data in events), events
        assert any(event == 'completed' and data['assistantMessage']['files'] for event, data in events), events
        unattached = upload(client, 'unattached.csv', b'private,data\nx,y\n')
        Model.denied_file = unattached['id']
        denied = client.request(f'/api/chat/sessions/{session["id"]}/messages', payload, {'Idempotency-Key': str(uuid.uuid4())})
        assert 'SANDBOX_FILE_NOT_ATTACHED' in denied['assistantMessage']['content'], denied
        Model.denied_file = None
        client.request(f'/api/agents/{agent["id"]}/sandbox', {'enabled': False}, method='PUT')
        try:
            client.request(f'/api/chat/sessions/{session["id"]}/messages', payload, {'Idempotency-Key': str(uuid.uuid4())})
            raise AssertionError('Disabled Agent accepted attachments')
        except urllib.error.HTTPError as error:
            assert error.code == 400
        workflow = client.request('/api/workflows', {'name': 'sandbox-chain-' + suffix, 'enabled': True})
        configs = [
            ('start', 'START', {}),
            ('copy', 'SANDBOX', {'operation': 'PYTHON', 'fileIds': [file['id']], 'code': "from pathlib import Path\nsource=next(Path('/input').iterdir())\nPath('/output/copied.csv').write_bytes(source.read_bytes())", 'timeoutSeconds': 20}),
            ('query', 'SANDBOX', {'operation': 'SQL', 'fileIds': '{{copy.artifact_ids}}', 'code': 'SELECT category,SUM(amount) AS total FROM input_1 GROUP BY category ORDER BY category', 'timeoutSeconds': 20}),
            ('end', 'END', {'outputTemplate': '{{query.sandbox_result}}'})]
        canvas = {'nodes': [{'name': key, 'nodeKey': key, 'nodeType': kind, 'configJson': json.dumps(config), 'positionX': i * 200, 'positionY': 0}
                            for i, (key, kind, config) in enumerate(configs)],
                  'edges': [{'sourceNodeIndex': i, 'targetNodeIndex': i + 1} for i in range(3)]}
        client.request(f'/api/workflows/{workflow["id"]}/canvas', canvas, method='PUT')
        raw = client.request(f'/api/workflows/{workflow["id"]}/test-run', {'userInput': 'Analyze sales', 'modelConfigId': model['id']}, raw=True)
        workflow_events = sse_events(raw)
        completed = next((data for event, data in workflow_events if event == 'completed'), None)
        assert completed is not None, workflow_events
        final = json.loads(completed['finalOutput'])
        assert final['status'] == 'SUCCEEDED' and any(file['name'] == 'result.xlsx' for file in final['artifacts']), final
        execution = next(data['executionId'] for event, data in workflow_events if event == 'started')
        old_task = final['taskId']
        resumed = sse_events(client.request(f'/api/workflows/executions/{execution}/resume', b'', raw=True))
        replayed = next((data for event, data in resumed if event == 'completed'), None)
        assert replayed is not None, resumed
        assert json.loads(replayed['finalOutput'])['taskId'] == old_task
        print('PASS: Agent tool planning, attachment scope, artifacts in history, chat idempotency, SSE progress, disabled capability, SANDBOX workflow chaining and checkpoint resume')
    finally:
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    main()
