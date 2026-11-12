/** 登录只检查输入非空；密码长度限制属于修改密码流程，不能限制已有账号。 */
export function loginValidationErrors(
  username: string,
  password: string,
): { username?: string; password?: string } {
  const errors: { username?: string; password?: string } = {};

  if (!username.trim()) errors.username = "请输入用户名";
  if (!password.trim()) errors.password = "请输入密码";

  return errors;
}
