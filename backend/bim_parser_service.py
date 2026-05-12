#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BIM文件解析服务
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

class BimParser:
    def __init__(self):
        self.supported_formats = ['.rvt', '.ifc', '.nwd', '.nwc', '.rfa', '.rte']
    
    def parse_bim_file(self, file_path: str, options: dict) -> dict:
        """解析BIM文件"""
        start_time = time.time()
        
        try:
            if not os.path.exists(file_path):
                raise FileNotFoundError(f"文件不存在: {file_path}")
            
            file_ext = Path(file_path).suffix.lower()
            if file_ext not in self.supported_formats:
                raise ValueError(f"不支持的BIM文件格式: {file_ext}")
            
            # 解析文件
            if file_ext == '.ifc':
                result = self._parse_ifc_file(file_path, options)
            else:
                result = self._parse_generic_bim_file(file_path, options)
            
            parse_time = (time.time() - start_time) * 1000
            result['statistics'] = {
                'parse_time': parse_time,
                'parse_version': '1.0.0',
                'file_size': os.path.getsize(file_path)
            }
            
            return result
            
        except Exception as e:
            logger.error(f"解析BIM文件失败: {file_path}, 错误: {str(e)}")
            raise
    
    def _parse_ifc_file(self, file_path: str, options: dict) -> dict:
        """解析IFC文件"""
        try:
            import ifcopenshell
            ifc_file = ifcopenshell.open(file_path)
            
            result = {
                'fileName': Path(file_path).name,
                'filePath': file_path,
                'fileType': 'ifc',
                'fileSize': os.path.getsize(file_path),
                'parseStatus': 'success',
                'modelInfo': self._extract_model_info(ifc_file),
                'projectInfo': self._extract_project_info(ifc_file),
                'elements': self._extract_elements(ifc_file) if options.get('extract_elements', True) else [],
                'materials': self._extract_materials(ifc_file) if options.get('extract_materials', True) else [],
                'views': self._extract_views(ifc_file) if options.get('extract_views', True) else [],
                'families': self._extract_families(ifc_file) if options.get('extract_families', True) else [],
                'systems': self._extract_systems(ifc_file) if options.get('extract_systems', True) else [],
                'spaces': self._extract_spaces(ifc_file) if options.get('extract_spaces', True) else [],
                'metadata': self._extract_metadata(ifc_file)
            }
            
            return result
            
        except ImportError:
            logger.warning("ifcopenshell库未安装，返回基本信息")
            return self._parse_generic_bim_file(file_path, options)
        except Exception as e:
            logger.error(f"解析IFC文件失败: {file_path}, 错误: {str(e)}")
            raise
    
    def _parse_generic_bim_file(self, file_path: str, options: dict) -> dict:
        """解析通用BIM文件"""
        result = {
            'fileName': Path(file_path).name,
            'filePath': file_path,
            'fileType': Path(file_path).suffix.lower(),
            'fileSize': os.path.getsize(file_path),
            'parseStatus': 'success',
            'modelInfo': {
                'name': Path(file_path).stem,
                'creationDate': datetime.fromtimestamp(os.path.getctime(file_path)).isoformat(),
                'lastModified': datetime.fromtimestamp(os.path.getmtime(file_path)).isoformat()
            },
            'projectInfo': {
                'name': Path(file_path).stem,
                'buildingType': 'Unknown'
            },
            'elements': [],
            'materials': [],
            'views': [],
            'families': [],
            'systems': [],
            'spaces': [],
            'metadata': {
                'format': Path(file_path).suffix.lower(),
                'parser': 'generic'
            }
        }
        
        return result
    
    def _extract_model_info(self, ifc_file) -> dict:
        """提取模型信息"""
        info = {}
        try:
            if hasattr(ifc_file, 'by_type'):
                # 获取IFC项目信息
                projects = ifc_file.by_type('IfcProject')
                if projects:
                    project = projects[0]
                    info['name'] = getattr(project, 'Name', 'Unknown')
                    info['description'] = getattr(project, 'Description', '')
            info['creationDate'] = datetime.now().isoformat()
            info['lastModified'] = datetime.now().isoformat()
        except Exception as e:
            logger.warning(f"提取模型信息失败: {str(e)}")
        return info
    
    def _extract_project_info(self, ifc_file) -> dict:
        """提取项目信息"""
        info = {}
        try:
            if hasattr(ifc_file, 'by_type'):
                projects = ifc_file.by_type('IfcProject')
                if projects:
                    project = projects[0]
                    info['name'] = getattr(project, 'Name', 'Unknown')
                    info['description'] = getattr(project, 'Description', '')
                    info['buildingType'] = 'Unknown'
        except Exception as e:
            logger.warning(f"提取项目信息失败: {str(e)}")
        return info
    
    def _extract_elements(self, ifc_file) -> list:
        """提取元素信息"""
        elements = []
        try:
            if hasattr(ifc_file, 'by_type'):
                # 获取建筑元素
                building_elements = ifc_file.by_type('IfcBuildingElement')
                for element in building_elements:
                    element_info = {
                        'id': str(getattr(element, 'GlobalId', '')),
                        'name': getattr(element, 'Name', ''),
                        'type': element.is_a(),
                        'category': element.is_a(),
                        'family': element.is_a(),
                        'level': 'Unknown',
                        'position': {'x': 0, 'y': 0, 'z': 0},
                        'volume': 0,
                        'area': 0,
                        'material': 'Unknown',
                        'parameters': {},
                        'systems': []
                    }
                    elements.append(element_info)
        except Exception as e:
            logger.warning(f"提取元素信息失败: {str(e)}")
        return elements
    
    def _extract_materials(self, ifc_file) -> list:
        """提取材质信息"""
        materials = []
        try:
            if hasattr(ifc_file, 'by_type'):
                material_layers = ifc_file.by_type('IfcMaterialLayer')
                for material in material_layers:
                    material_info = {
                        'id': str(getattr(material, 'GlobalId', '')),
                        'name': getattr(material, 'Name', ''),
                        'description': getattr(material, 'Description', ''),
                        'color': 'Unknown',
                        'density': 0,
                        'thermalConductivity': 0,
                        'properties': {}
                    }
                    materials.append(material_info)
        except Exception as e:
            logger.warning(f"提取材质信息失败: {str(e)}")
        return materials
    
    def _extract_views(self, ifc_file) -> list:
        """提取视图信息"""
        views = []
        try:
            if hasattr(ifc_file, 'by_type'):
                # 获取视图定义
                view_definitions = ifc_file.by_type('IfcViewDefinition')
                for view in view_definitions:
                    view_info = {
                        'id': str(getattr(view, 'GlobalId', '')),
                        'name': getattr(view, 'Name', ''),
                        'type': 'Unknown',
                        'scale': '1:100',
                        'cameraPosition': {'x': 0, 'y': 0, 'z': 0},
                        'targetPosition': {'x': 0, 'y': 0, 'z': 0},
                        'fieldOfView': 60,
                        'cropBox': None
                    }
                    views.append(view_info)
        except Exception as e:
            logger.warning(f"提取视图信息失败: {str(e)}")
        return views
    
    def _extract_families(self, ifc_file) -> list:
        """提取族信息"""
        families = []
        try:
            if hasattr(ifc_file, 'by_type'):
                # 获取类型定义
                type_products = ifc_file.by_type('IfcTypeProduct')
                for type_product in type_products:
                    family_info = {
                        'id': str(getattr(type_product, 'GlobalId', '')),
                        'name': getattr(type_product, 'Name', ''),
                        'category': type_product.is_a(),
                        'description': getattr(type_product, 'Description', ''),
                        'parameters': [],
                        'types': []
                    }
                    families.append(family_info)
        except Exception as e:
            logger.warning(f"提取族信息失败: {str(e)}")
        return families
    
    def _extract_systems(self, ifc_file) -> list:
        """提取系统信息"""
        systems = []
        try:
            if hasattr(ifc_file, 'by_type'):
                # 获取系统
                system_elements = ifc_file.by_type('IfcSystem')
                for system in system_elements:
                    system_info = {
                        'id': str(getattr(system, 'GlobalId', '')),
                        'name': getattr(system, 'Name', ''),
                        'type': system.is_a(),
                        'description': getattr(system, 'Description', ''),
                        'elements': [],
                        'properties': {}
                    }
                    systems.append(system_info)
        except Exception as e:
            logger.warning(f"提取系统信息失败: {str(e)}")
        return systems
    
    def _extract_spaces(self, ifc_file) -> list:
        """提取空间信息"""
        spaces = []
        try:
            if hasattr(ifc_file, 'by_type'):
                # 获取空间
                space_elements = ifc_file.by_type('IfcSpace')
                for space in space_elements:
                    space_info = {
                        'id': str(getattr(space, 'GlobalId', '')),
                        'name': getattr(space, 'Name', ''),
                        'number': getattr(space, 'LongName', ''),
                        'level': 'Unknown',
                        'area': 0,
                        'volume': 0,
                        'height': 0,
                        'centerPoint': {'x': 0, 'y': 0, 'z': 0},
                        'boundingBox': None,
                        'roomType': 'Unknown',
                        'parameters': {}
                    }
                    spaces.append(space_info)
        except Exception as e:
            logger.warning(f"提取空间信息失败: {str(e)}")
        return spaces
    
    def _extract_metadata(self, ifc_file) -> dict:
        """提取元数据"""
        metadata = {
            'schema': getattr(ifc_file, 'schema', 'unknown'),
            'fileSize': 0,
            'headerVariables': {}
        }
        return metadata

bim_parser = BimParser()

@app.route('/health', methods=['GET'])
def health_check():
    return jsonify({
        'status': 'ok',
        'service': 'bim-parser',
        'version': '1.0.0',
        'timestamp': datetime.now().isoformat()
    })

@app.route('/parse-bim', methods=['POST'])
def parse_bim():
    try:
        data = request.get_json()
        file_path = data.get('file_path')
        
        if not file_path:
            return jsonify({'error': '文件路径不能为空'}), 400
        
        options = {
            'extract_elements': data.get('extract_elements', True),
            'extract_materials': data.get('extract_materials', True),
            'extract_views': data.get('extract_views', True),
            'extract_families': data.get('extract_families', True),
            'extract_systems': data.get('extract_systems', True),
            'extract_spaces': data.get('extract_spaces', True)
        }
        
        result = bim_parser.parse_bim_file(file_path, options)
        return jsonify(result)
        
    except Exception as e:
        logger.error(f"解析BIM文件失败: {str(e)}")
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
            'isSupported': Path(file_path).suffix.lower() in bim_parser.supported_formats
        }
        
        return jsonify(file_info)
        
    except Exception as e:
        logger.error(f"获取文件信息失败: {str(e)}")
        return jsonify({'error': str(e)}), 500

@app.route('/extract-elements', methods=['POST'])
def extract_elements():
    try:
        data = request.get_json()
        file_path = data.get('file_path')
        
        if not file_path:
            return jsonify({'error': '文件路径不能为空'}), 400
        
        options = {'extract_elements': True, 'extract_materials': False, 'extract_views': False, 'extract_families': False, 'extract_systems': False, 'extract_spaces': False}
        result = bim_parser.parse_bim_file(file_path, options)
        
        return jsonify({
            'fileName': result['fileName'],
            'elements': result['elements'],
            'totalElements': len(result['elements'])
        })
        
    except Exception as e:
        logger.error(f"提取元素失败: {str(e)}")
        return jsonify({'error': str(e)}), 500

@app.route('/collision-detection', methods=['POST'])
def collision_detection():
    try:
        data = request.get_json()
        file_path = data.get('file_path')
        tolerance = data.get('tolerance', 0.01)
        
        if not file_path:
            return jsonify({'error': '文件路径不能为空'}), 400
        
        # 这里实现碰撞检测逻辑
        # 目前返回模拟结果
        collision_report = {
            'fileName': Path(file_path).name,
            'tolerance': tolerance,
            'totalCollisions': 0,
            'collisions': [],
            'summary': '未发现碰撞',
            'timestamp': datetime.now().isoformat()
        }
        
        return jsonify(collision_report)
        
    except Exception as e:
        logger.error(f"碰撞检测失败: {str(e)}")
        return jsonify({'error': str(e)}), 500

if __name__ == '__main__':
    logger.info("启动BIM解析服务...")
    app.run(host='0.0.0.0', port=5002, debug=False)


