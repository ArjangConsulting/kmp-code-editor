import UIKit
import EditorVerificationKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Editor", sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = VerificationViewControllerKt.verificationViewController { [weak window] result in
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                guard let responder = self.textResponder(in: window), let input = responder as? UITextInput else {
                    result("FAIL: focus the editor first")
                    return
                }
                input.setMarkedText("に", selectedRange: NSRange(location: 1, length: 0))
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                    let firstMarked = input.markedTextRange != nil
                    input.setMarkedText("にほ", selectedRange: NSRange(location: 2, length: 0))
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        let secondMarked = input.markedTextRange != nil
                        input.setMarkedText("日本", selectedRange: NSRange(location: 2, length: 0))
                        input.unmarkText()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                            result("native UITextInput; marked=\(firstMarked),\(secondMarked); committed=日本")
                        }
                    }
                }
            }
        }
        window.makeKeyAndVisible()
        self.window = window
    }

    private func textResponder(in view: UIView?) -> UIView? {
        guard let view else { return nil }
        if view.isFirstResponder && view is UITextInput { return view }
        return view.subviews.lazy.compactMap { self.textResponder(in: $0) }.first
    }
}
