"""医院本地账号认证与患者级数据访问权限。"""

from .models import HospitalPrincipal, LoginResult
from .service import HospitalAuthService

__all__ = ["HospitalAuthService", "HospitalPrincipal", "LoginResult"]
