#!/usr/bin/env python3
"""
Llama 模型切换管理服务
用于在后端无法执行 Docker 命令时，代理模型切换请求

启动方式: python llama-manager.py
默认监听: http://0.0.0.0:5000
"""

from flask import Flask, request, jsonify
import subprocess
import os
import sys

app = Flask(__name__)

# 配置路径 - 根据实际情况修改
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODELS_DIR = os.path.abspath(os.path.join(BASE_DIR, '..', 'models'))
CHAT_CACHE_DIR = os.path.abspath(os.path.join(BASE_DIR, 'backend', 'llama-cache', 'chat'))
EMBEDDING_CACHE_DIR = os.path.abspath(os.path.join(BASE_DIR, 'backend', 'llama-cache', 'embedding'))
NETWORK = 'ls-zgt_app-network'
IMAGE = 'ghcr.io/ggml-org/llama.cpp:server-cuda'

def run_command(cmd, timeout=120):
    """执行命令并返回结果"""
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            shell=isinstance(cmd, str)
        )
        return {'success': result.returncode == 0, 'output': result.stdout, 'error': result.stderr}
    except subprocess.TimeoutExpired:
        return {'success': False, 'output': '', 'error': 'Command timeout'}
    except Exception as e:
        return {'success': False, 'output': '', 'error': str(e)}

def ensure_dirs():
    """确保必要目录存在"""
    for d in [CHAT_CACHE_DIR, EMBEDDING_CACHE_DIR]:
        os.makedirs(d, exist_ok=True)

def switch_chat_model(model_path):
    """切换 Chat 模型"""
    # 停止并删除旧容器
    run_command(['docker', 'stop', 'llama-chat'], timeout=30)
    run_command(['docker', 'rm', 'llama-chat'], timeout=10)

    # 构建启动命令 - 使用 /models 作为容器内模型路径
    # model_path 是完整宿主机路径，需要提取相对路径部分
    relative_path = os.path.relpath(model_path, MODELS_DIR)
    container_model_path = '/models/' + relative_path.replace('\\', '/')

    cmd = [
        'docker', 'run', '-d',
        '--name', 'llama-chat',
        '-p', '8081:8080',
        '-v', f'{MODELS_DIR}:/models',
        '-v', f'{CHAT_CACHE_DIR}:/root/.cache/llama',
        '--gpus', 'all',
        '--network', NETWORK,
        IMAGE,
        '-m', container_model_path,
        '--port', '8080',
        '--host', '0.0.0.0',
        '-ngl', '100',
        '-c', '8192'
    ]

    result = run_command(cmd, timeout=120)
    return result

def switch_embedding_model(model_path):
    """切换 Embedding 模型"""
    # 停止并删除旧容器
    run_command(['docker', 'stop', 'llama-embedding'], timeout=30)
    run_command(['docker', 'rm', 'llama-embedding'], timeout=10)

    # 构建启动命令 - 使用 /models 作为容器内模型路径
    # model_path 是完整宿主机路径，需要提取相对路径部分
    relative_path = os.path.relpath(model_path, MODELS_DIR)
    container_model_path = '/models/' + relative_path.replace('\\', '/')

    cmd = [
        'docker', 'run', '-d',
        '--name', 'llama-embedding',
        '-p', '8082:8080',
        '-v', f'{MODELS_DIR}:/models',
        '-v', f'{EMBEDDING_CACHE_DIR}:/root/.cache/llama',
        '--gpus', 'all',
        '--network', NETWORK,
        IMAGE,
        '-m', container_model_path,
        '--port', '8080',
        '--host', '0.0.0.0',
        '-ngl', '100',
        '-c', '8192',
        '--embedding'
    ]

    result = run_command(cmd, timeout=120)
    return result

@app.route('/switch', methods=['POST'])
def switch_model():
    """切换模型接口"""
    try:
        data = request.get_json()
        service = data.get('service', 'chat')
        model_path = data.get('model', '')

        if not model_path:
            return jsonify({'success': False, 'message': '模型路径不能为空'}), 400

        # 将容器内路径 /models 转换为宿主机实际路径
        if model_path.startswith('/models/'):
            # 提取相对路径并拼接宿主机模型目录
            relative_path = model_path[8:]  # 去掉 '/models/'
            host_model_path = os.path.join(MODELS_DIR, relative_path.replace('/', os.sep))
        elif model_path.startswith('/host_models/'):
            # 去掉 /host_models/ 前缀，拼接宿主机模型目录
            relative_path = model_path[13:]  # 去掉 '/host_models/'
            host_model_path = os.path.join(MODELS_DIR, relative_path.replace('/', os.sep))
        else:
            host_model_path = model_path

        # 验证模型文件是否存在
        if not os.path.exists(host_model_path):
            return jsonify({'success': False, 'message': f'模型文件不存在: {host_model_path}'}), 400

        print(f"[Switch] {service} -> {model_path} (host: {host_model_path})")

        if service == 'chat':
            result = switch_chat_model(host_model_path)
        elif service == 'embedding':
            result = switch_embedding_model(host_model_path)
        else:
            return jsonify({'success': False, 'message': f'未知服务类型: {service}'}), 400

        if result['success']:
            return jsonify({
                'success': True,
                'message': f'{service} 模型切换成功',
                'service': service,
                'model': model_path
            })
        else:
            return jsonify({
                'success': False,
                'message': f'切换失败: {result.get("error", "未知错误")}'
            }), 500

    except Exception as e:
        return jsonify({'success': False, 'message': f'请求处理失败: {str(e)}'}), 500

@app.route('/status', methods=['GET'])
def get_status():
    """获取当前模型状态"""
    def get_container_info(name):
        result = run_command(['docker', 'inspect', '--format', '{{.State.Status}}|{{.Config.Image}}|{{.Config.Cmd}}', name])
        if result['success'] and result['output'].strip():
            parts = result['output'].strip().split('|')
            return {
                'status': parts[0] if len(parts) > 0 else 'unknown',
                'image': parts[1] if len(parts) > 1 else '',
                'model': parts[2].split('-m')[-1].split()[0] if '-m' in parts[2] else '' if len(parts) > 2 else ''
            }
        return {'status': 'not found', 'image': '', 'model': ''}

    return jsonify({
        'success': True,
        'chat': get_container_info('llama-chat'),
        'embedding': get_container_info('llama-embedding')
    })

@app.route('/health', methods=['GET'])
def health():
    """健康检查"""
    return jsonify({'status': 'ok', 'service': 'llama-manager'})

@app.route('/', methods=['GET'])
def index():
    """首页"""
    return jsonify({
        'service': 'Llama Model Manager',
        'version': '1.0.0',
        'endpoints': [
            'POST /switch - 切换模型',
            'GET /status - 获取状态',
            'GET /health - 健康检查'
        ]
    })

if __name__ == '__main__':
    print("=" * 50)
    print("Llama 模型管理器")
    print(f"模型目录: {MODELS_DIR}")
    print(f"监听地址: http://0.0.0.0:5000")
    print("=" * 50)

    ensure_dirs()

    # 检查 docker 是否可用
    result = run_command(['docker', '--version'])
    if result['success']:
        print(f"Docker 版本: {result['output'].strip()}")
    else:
        print("警告: Docker 不可用!")

    app.run(host='0.0.0.0', port=5000, debug=False)
