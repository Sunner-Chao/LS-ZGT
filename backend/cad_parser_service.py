#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CAD文件解析服务
"""

import os
import json
import time
import logging
from pathlib import Path
from datetime import datetime
from flask import Flask, request, jsonify
from flask_cors import CORS

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)
CORS(app)

class CadParser:
    def __init__(self):
        self.supported_formats = ['.dwg', '.dxf', '.dgn', '.dwf']
    
    def parse_cad_file(self, file_path: str, options: dict) -> dict:
        """解析CAD文件"""
        start_time = time.time()
        
        try:
            if not os.path.exists(file_path):
                raise FileNotFoundError(f"文件不存在: {file_path}")
            
            file_ext = Path(file_path).suffix.lower()
            if file_ext not in self.supported_formats:
                raise ValueError(f"不支持的CAD文件格式: {file_ext}")
            
            # 解析文件
            if file_ext == '.dxf':
                result = self._parse_dxf_file(file_path, options)
            else:
                result = self._parse_generic_cad_file(file_path, options)
            
            parse_time = (time.time() - start_time) * 1000
            result['statistics'] = {
                'parse_time': parse_time,
                'parse_version': '1.0.0',
                'file_size': os.path.getsize(file_path)
            }
            
            return result
            
        except Exception as e:
            logger.error(f"解析CAD文件失败: {file_path}, 错误: {str(e)}")
            raise
    
    def _parse_dxf_file(self, file_path: str, options: dict) -> dict:
        """解析DXF文件"""
        try:
            import ezdxf
            doc = ezdxf.readfile(file_path)
            
            result = {
                'fileName': Path(file_path).name,
                'filePath': file_path,
                'fileType': 'dxf',
                'fileSize': os.path.getsize(file_path),
                'parseStatus': 'success',
                'drawingInfo': self._extract_drawing_info(doc),
                'layers': self._extract_layers(doc) if options.get('extract_layers', True) else [],
                'entities': self._extract_entities(doc) if options.get('extract_entities', True) else [],
                'texts': self._extract_texts(doc) if options.get('extract_text', True) else [],
                'annotations': self._extract_annotations(doc) if options.get('extract_dimensions', True) else [],
                'metadata': self._extract_metadata(doc)
            }
            
            return result
            
        except ImportError:
            logger.warning("ezdxf库未安装，返回基本信息")
            return self._parse_generic_cad_file(file_path, options)
        except Exception as e:
            logger.error(f"解析DXF文件失败: {file_path}, 错误: {str(e)}")
            raise
    
    def _parse_generic_cad_file(self, file_path: str, options: dict) -> dict:
        """解析通用CAD文件"""
        result = {
            'fileName': Path(file_path).name,
            'filePath': file_path,
            'fileType': Path(file_path).suffix.lower(),
            'fileSize': os.path.getsize(file_path),
            'parseStatus': 'success',
            'drawingInfo': {
                'title': Path(file_path).stem,
                'creationDate': datetime.fromtimestamp(os.path.getctime(file_path)).isoformat(),
                'lastModified': datetime.fromtimestamp(os.path.getmtime(file_path)).isoformat()
            },
            'layers': [],
            'entities': [],
            'texts': [],
            'annotations': [],
            'metadata': {
                'format': Path(file_path).suffix.lower(),
                'parser': 'generic'
            }
        }
        
        return result
    
    def _extract_drawing_info(self, doc) -> dict:
        """提取图纸基本信息"""
        info = {}
        try:
            if hasattr(doc, 'header'):
                if 'TITLE' in doc.header:
                    info['title'] = doc.header['TITLE']
                if 'AUTHOR' in doc.header:
                    info['author'] = doc.header['AUTHOR']
            info['creationDate'] = datetime.now().isoformat()
            info['lastModified'] = datetime.now().isoformat()
        except Exception as e:
            logger.warning(f"提取图纸信息失败: {str(e)}")
        return info
    
    def _extract_layers(self, doc) -> list:
        """提取图层信息"""
        layers = []
        try:
            if hasattr(doc, 'layers'):
                for layer in doc.layers:
                    layer_info = {
                        'name': layer.dxf.name,
                        'color': 'white',
                        'linetype': 'continuous',
                        'lineweight': 0,
                        'isVisible': True,
                        'isLocked': False,
                        'entityCount': 0
                    }
                    layers.append(layer_info)
        except Exception as e:
            logger.warning(f"提取图层信息失败: {str(e)}")
        return layers
    
    def _extract_entities(self, doc) -> list:
        """提取实体信息"""
        entities = []
        try:
            if hasattr(doc, 'modelspace'):
                msp = doc.modelspace()
                for entity in msp:
                    entity_info = {
                        'id': str(getattr(entity.dxf, 'handle', '')),
                        'type': entity.dxftype(),
                        'layer': getattr(entity.dxf, 'layer', ''),
                        'color': 'white',
                        'linetype': 'continuous',
                        'lineweight': 0,
                        'points': [],
                        'properties': {}
                    }
                    entities.append(entity_info)
        except Exception as e:
            logger.warning(f"提取实体信息失败: {str(e)}")
        return entities
    
    def _extract_texts(self, doc) -> list:
        """提取文本信息"""
        texts = []
        try:
            if hasattr(doc, 'modelspace'):
                msp = doc.modelspace()
                for entity in msp:
                    if entity.dxftype() in ['TEXT', 'MTEXT']:
                        text_info = {
                            'id': str(getattr(entity.dxf, 'handle', '')),
                            'content': getattr(entity.dxf, 'text', ''),
                            'position': {'x': 0, 'y': 0, 'z': 0},
                            'height': getattr(entity.dxf, 'height', 0),
                            'font': 'Standard',
                            'rotation': getattr(entity.dxf, 'rotation', 0),
                            'layer': getattr(entity.dxf, 'layer', ''),
                            'color': 'white'
                        }
                        texts.append(text_info)
        except Exception as e:
            logger.warning(f"提取文本信息失败: {str(e)}")
        return texts
    
    def _extract_annotations(self, doc) -> list:
        """提取标注信息"""
        annotations = []
        try:
            if hasattr(doc, 'modelspace'):
                msp = doc.modelspace()
                for entity in msp:
                    if entity.dxftype() in ['DIMENSION', 'LEADER']:
                        annotation_info = {
                            'id': str(getattr(entity.dxf, 'handle', '')),
                            'type': entity.dxftype(),
                            'content': '',
                            'position': {'x': 0, 'y': 0, 'z': 0},
                            'height': 0,
                            'font': 'Standard',
                            'rotation': 0,
                            'layer': getattr(entity.dxf, 'layer', '')
                        }
                        annotations.append(annotation_info)
        except Exception as e:
            logger.warning(f"提取标注信息失败: {str(e)}")
        return annotations
    
    def _extract_metadata(self, doc) -> dict:
        """提取元数据"""
        metadata = {
            'dxfVersion': getattr(doc, 'dxfversion', 'unknown'),
            'encoding': getattr(doc, 'encoding', 'unknown'),
            'headerVariables': {}
        }
        return metadata

cad_parser = CadParser()

@app.route('/health', methods=['GET'])
def health_check():
    return jsonify({
        'status': 'ok',
        'service': 'cad-parser',
        'version': '1.0.0',
        'timestamp': datetime.now().isoformat()
    })

@app.route('/parse-cad', methods=['POST'])
def parse_cad():
    try:
        data = request.get_json()
        file_path = data.get('file_path')
        
        if not file_path:
            return jsonify({'error': '文件路径不能为空'}), 400
        
        options = {
            'extract_text': data.get('extract_text', True),
            'extract_dimensions': data.get('extract_dimensions', True),
            'extract_layers': data.get('extract_layers', True),
            'extract_entities': data.get('extract_entities', True)
        }
        
        result = cad_parser.parse_cad_file(file_path, options)
        return jsonify(result)
        
    except Exception as e:
        logger.error(f"解析CAD文件失败: {str(e)}")
        return jsonify({'error': str(e)}), 500

@app.route('/file-info', methods=['GET'])
def get_file_info():
    try:
        file_path = request.args.get('file_path')
        
        if not file_path:
            return jsonify({'error': '文件路径不能为空'}), 400
        
        if not os.path.exists(file_path):
            return jsonify({'error': '文件不存在'}), 404
        
        file_info = {
            'fileName': Path(file_path).name,
            'filePath': file_path,
            'fileType': Path(file_path).suffix.lower(),
            'fileSize': os.path.getsize(file_path),
            'creationDate': datetime.fromtimestamp(os.path.getctime(file_path)).isoformat(),
            'lastModified': datetime.fromtimestamp(os.path.getmtime(file_path)).isoformat(),
            'isSupported': Path(file_path).suffix.lower() in cad_parser.supported_formats
        }
        
        return jsonify(file_info)
        
    except Exception as e:
        logger.error(f"获取文件信息失败: {str(e)}")
        return jsonify({'error': str(e)}), 500

@app.route('/extract-text', methods=['POST'])
def extract_text():
    try:
        data = request.get_json()
        file_path = data.get('file_path')
        
        if not file_path:
            return jsonify({'error': '文件路径不能为空'}), 400
        
        options = {'extract_text': True, 'extract_layers': False, 'extract_entities': False, 'extract_dimensions': False}
        result = cad_parser.parse_cad_file(file_path, options)
        
        return jsonify({
            'fileName': result['fileName'],
            'texts': result['texts'],
            'totalTexts': len(result['texts'])
        })
        
    except Exception as e:
        logger.error(f"提取文本失败: {str(e)}")
        return jsonify({'error': str(e)}), 500

if __name__ == '__main__':
    logger.info("启动CAD解析服务...")
    app.run(host='0.0.0.0', port=5001, debug=False)
